package com.personallifeos.mobile.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import com.personallifeos.mobile.widgets.AgendaWidgetReceiver
import com.personallifeos.mobile.widgets.InboxWidgetReceiver
import com.personallifeos.mobile.widgets.MonthWidgetReceiver
import com.personallifeos.mobile.widgets.DayWidgetReceiver

/**
 * Minimal debug-only host for exercising Glance widgets with adb.
 *
 * Before launching, allow this package to bind widgets on an emulator:
 * `adb shell appwidget grantbind --package com.personallifeos.mobile.debug --user 0`.
 * Pass `-e provider inbox|agenda|day|month` to select a provider.
 */
class WidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var root: FrameLayout
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var hostId = 0
    private var awaitingConfiguration = false

    private val manager: AppWidgetManager
        get() = AppWidgetManager.getInstance(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)

        // CLEAR_TASK starts a fresh host; a saved-state recreation keeps the widget's host
        // ownership so its restored appWidgetId remains valid across rotation.
        hostId = savedInstanceState?.getInt(KEY_HOST_ID)?.takeIf { it > 0 } ?: nextHostId()
        host = AppWidgetHost(this, hostId)
        host.startListening()
        appWidgetId = savedInstanceState?.getInt(KEY_APP_WIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        awaitingConfiguration = savedInstanceState?.getBoolean(KEY_AWAITING_CONFIGURATION) == true

        val provider = providerFromIntent()
        val info = manager.installedProviders.firstOrNull { it.provider == provider }
        if (info == null) {
            finishWithMessage("Provider is not installed: ${provider.flattenToShortString()}")
            return
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            manager.getAppWidgetInfo(appWidgetId)?.provider != provider
        ) {
            deleteWidgetId()
            appWidgetId = host.allocateAppWidgetId()
            val allowed = manager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
            if (!allowed) {
                deleteWidgetId()
                finishWithMessage("Binding denied. Run adb shell appwidget grantbind first.")
                return
            }
        }

        if (savedInstanceState == null && intent.getBooleanExtra("configure", false) &&
            info.configure != null
        ) {
            launchConfiguration(info.configure!!)
        } else if (!awaitingConfiguration) {
            displayWidget(info)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_APP_WIDGET_ID, appWidgetId)
        outState.putInt(KEY_HOST_ID, hostId)
        outState.putBoolean(KEY_AWAITING_CONFIGURATION, awaitingConfiguration)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || awaitingConfiguration) return
        if (!newIntent.hasExtra("widthDp") && !newIntent.hasExtra("heightDp")) return

        // Keep the original provider/component and configured widget ID. Only dimensions are live.
        copyDimensionExtra(newIntent, "widthDp")
        copyDimensionExtra(newIntent, "heightDp")
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return
        displayWidget(info)
    }

    @Deprecated("Deprecated in Android API Activity, retained for debug-host compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONFIGURE_REQUEST_CODE) return

        awaitingConfiguration = false
        if (resultCode != RESULT_OK) {
            deleteWidgetId()
            finishWithMessage("Widget configuration was cancelled.")
            return
        }

        val info = manager.getAppWidgetInfo(appWidgetId)
        if (info == null) {
            deleteWidgetId()
            finishWithMessage("Configured widget is no longer available.")
            return
        }
        displayWidget(info)
    }

    private fun launchConfiguration(configure: ComponentName) {
        awaitingConfiguration = true
        val configurationIntent = Intent().setComponent(configure).putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            appWidgetId,
        )
        try {
            startActivityForResult(configurationIntent, CONFIGURE_REQUEST_CODE)
        } catch (_: ActivityNotFoundException) {
            awaitingConfiguration = false
            deleteWidgetId()
            finishWithMessage("Widget configuration activity is not available.")
        }
    }

    private fun displayWidget(info: android.appwidget.AppWidgetProviderInfo) {
        root.removeAllViews()
        val widget = host.createView(this, appWidgetId, info) ?: run {
            deleteWidgetId()
            finishWithMessage("AppWidgetHost could not create a view")
            return
        }
        widget.setAppWidget(appWidgetId, info)
        val density = resources.displayMetrics.density
        val widthDp = dimensionDp("widthDp")
            ?: (resources.displayMetrics.widthPixels / density - 24).coerceAtLeast(1f)
        val heightDp = dimensionDp("heightDp")
            ?: if (intent.getStringExtra("provider") == "inbox") 280f else 360f
        val params = FrameLayout.LayoutParams(
            (widthDp * density).toInt(), (heightDp * density).toInt(),
        ).apply { leftMargin = (12 * density).toInt(); topMargin = (48 * density).toInt() }
        root.setBackgroundColor(android.graphics.Color.rgb(242, 243, 248))
        root.addView(widget, params)
        widget.updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            deleteWidgetId()
            if (::host.isInitialized) host.deleteHost()
        }
        if (::host.isInitialized) host.stopListening()
        super.onDestroy()
    }

    private fun nextHostId(): Int {
        val preferences = getSharedPreferences(HOST_PREFS, MODE_PRIVATE)
        val previous = preferences.getInt(KEY_NEXT_HOST_ID, 0)
        val next = if (previous >= Int.MAX_VALUE - 1) 1 else previous + 1
        check(preferences.edit().putInt(KEY_NEXT_HOST_ID, next).commit()) {
            "Could not persist debug widget host ID"
        }
        return next
    }

    private fun providerFromIntent(): ComponentName {
        val flattened = intent.getStringExtra("component")?.trim()
        if (!flattened.isNullOrEmpty()) {
            // The component is only accepted after comparing it with the installed provider list.
            val requested = ComponentName.unflattenFromString(flattened)
            return manager.installedProviders.firstOrNull { it.provider == requested }?.provider
                ?: requested ?: ComponentName("invalid.widget.provider", "invalid.widget.Provider")
        }
        return provider(intent.getStringExtra("provider"))
    }

    private fun provider(value: String?): ComponentName = when (value?.lowercase()) {
        "agenda" -> ComponentName(this, AgendaWidgetReceiver::class.java)
        "day" -> ComponentName(this, DayWidgetReceiver::class.java)
        "month" -> ComponentName(this, MonthWidgetReceiver::class.java)
        else -> ComponentName(this, InboxWidgetReceiver::class.java)
    }

    private fun dimensionDp(extra: String): Float? {
        val value = intent.getIntExtra(extra, Int.MIN_VALUE)
        if (value != Int.MIN_VALUE) return value.toFloat().takeIf { it > 0 }
        return intent.getStringExtra(extra)?.toFloatOrNull()?.takeIf { it > 0 }
    }

    private fun copyDimensionExtra(source: Intent, key: String) {
        if (!source.hasExtra(key)) return
        val value = source.extras?.get(key)
        when (value) {
            is Int -> intent.putExtra(key, value)
            is Number -> intent.putExtra(key, value.toInt())
            is String -> intent.putExtra(key, value)
        }
    }

    private fun deleteWidgetId() {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && ::host.isInitialized) {
            host.deleteAppWidgetId(appWidgetId)
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
    }

    private fun finishWithMessage(text: String) {
        root.removeAllViews()
        root.addView(message(text))
    }

    private fun message(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(32, 32, 32, 32)
    }

    private companion object {
        const val CONFIGURE_REQUEST_CODE = 0x5043
        const val KEY_APP_WIDGET_ID = "widget_host_app_widget_id"
        const val KEY_HOST_ID = "widget_host_id"
        const val KEY_AWAITING_CONFIGURATION = "widget_host_awaiting_configuration"
        const val HOST_PREFS = "widget_host_debug_state"
        const val KEY_NEXT_HOST_ID = "next_host_id"
    }
}
