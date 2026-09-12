package com.personallifeos.mobile.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.personallifeos.mobile.data.LifeRepository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** The launcher is singleTop in the manifest. Widget deep links are handled here as well. */
class MainActivity : ComponentActivity() {
    private lateinit var repository: LifeRepository
    private var deepLink by mutableStateOf(DeepLink.fromIntent(null))
    private var deepLinkRequestId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LifeRepository.get(this)
        deepLink = DeepLink.fromIntent(intent, ++deepLinkRequestId)
        setContent {
            LifeOsApp(repository, deepLink) { deepLink = it }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = DeepLink.fromIntent(intent, ++deepLinkRequestId)
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) lifecycleScope.launch {
            // Loading an empty repository must not manufacture a red "Подключите сервер"
            // error on the login screen. Sync only after a persisted account is available.
            val connected = try { repository.snapshot().connected } catch (_: Exception) { false }
            if (connected) repository.sync()
        }
    }
}

data class DeepLink(
    val screen: String = "inbox",
    val id: String? = null,
    val date: String? = null,
    val captureBoardId: String? = null,
    val captureColumnId: String? = null,
    val requestId: Long = 0L,
    val time: String? = null,
) {
    companion object {
        fun fromIntent(intent: android.content.Intent?, requestId: Long = 0L): DeepLink = DeepLink(
            screen = intent?.getStringExtra("screen") ?: "inbox",
            id = intent?.getStringExtra("id"),
            date = intent?.getStringExtra("date"),
            captureBoardId = intent?.getStringExtra("captureBoardId"),
            captureColumnId = intent?.getStringExtra("captureColumnId"),
            requestId = requestId,
            time = intent?.getStringExtra("time")?.let { value ->
                runCatching { java.time.LocalTime.parse(value).toString() }.getOrNull()
            },
        )
    }
}
