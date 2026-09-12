package com.personallifeos.mobile.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.personallifeos.mobile.widgets.AgendaWidgetReceiver
import com.personallifeos.mobile.widgets.InboxWidgetReceiver
import com.personallifeos.mobile.widgets.MonthWidgetReceiver

/**
 * Minimal debug-only host for exercising Glance widgets with adb.
 *
 * Before launching, allow this package to bind widgets on an emulator:
 * `adb shell appwidget grantbind --package com.personallifeos.mobile.debug --user 0`.
 * Pass `-e provider inbox|agenda|month` to select a provider.
 */
class WidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        setContentView(root)

        host = AppWidgetHost(this, HOST_ID)
        host.startListening()
        val manager = AppWidgetManager.getInstance(this)
        val provider = provider(intent.getStringExtra("provider"))
        val info = manager.installedProviders.firstOrNull { it.provider == provider }
        if (info == null) {
            root.addView(message("Provider is not installed: ${provider.className}"))
            return
        }

        appWidgetId = host.allocateAppWidgetId()
        val allowed = manager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
        if (!allowed) {
            host.deleteAppWidgetId(appWidgetId)
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            root.addView(message("Binding denied. Run adb shell appwidget grantbind first."))
            return
        }

        val widget = host.createView(this, appWidgetId, info) ?: run {
            root.addView(message("AppWidgetHost could not create a view"))
            return
        }
        widget.setAppWidget(appWidgetId, info)
        val density = resources.displayMetrics.density
        val widthDp = resources.displayMetrics.widthPixels / density - 24
        val heightDp = if (intent.getStringExtra("provider") == "inbox") 280 else 360
        val params = FrameLayout.LayoutParams(
            (widthDp * density).toInt(), (heightDp * density).toInt(),
        ).apply { leftMargin = (12 * density).toInt(); topMargin = (48 * density).toInt() }
        root.setBackgroundColor(android.graphics.Color.rgb(242, 243, 248))
        root.addView(widget, params)
        widget.updateAppWidgetSize(null, widthDp.toInt(), heightDp, widthDp.toInt(), heightDp)
    }

    override fun onDestroy() {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(appWidgetId)
        if (::host.isInitialized) host.stopListening()
        super.onDestroy()
    }

    private fun provider(value: String?): ComponentName = when (value?.lowercase()) {
        "agenda", "day" -> ComponentName(this, AgendaWidgetReceiver::class.java)
        "month" -> ComponentName(this, MonthWidgetReceiver::class.java)
        else -> ComponentName(this, InboxWidgetReceiver::class.java)
    }

    private fun message(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(32, 32, 32, 32)
    }

    private companion object {
        const val HOST_ID = 0x504c4f53
    }
}
