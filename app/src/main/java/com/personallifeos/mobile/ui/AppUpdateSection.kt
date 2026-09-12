package com.personallifeos.mobile.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.personallifeos.mobile.BuildConfig
import com.personallifeos.mobile.updates.AppUpdateManager
import com.personallifeos.mobile.updates.UpdatePhase
import com.personallifeos.mobile.updates.UpdateInstaller
import java.io.File

@Composable
fun AppUpdateSection(
    manager: AppUpdateManager,
    baseUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by manager.state.collectAsState()
    var pendingInstallPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingInstallPath?.let(::File)
        pendingInstallPath = null
        if (file != null && file.path == state.apkFile?.path && UpdateInstaller.canInstall(context)) {
            installError = launchInstaller(context, file)
        } else if (file != null && !UpdateInstaller.canInstall(context)) {
            installError = "Разрешение на установку не выдано"
        } else if (file != null) {
            installError = "Файл обновления больше недоступен"
        }
    }

    fun requestInstall(file: File) {
        installError = null
        if (UpdateInstaller.canInstall(context)) {
            installError = launchInstaller(context, file)
        } else {
            pendingInstallPath = file.path
            try {
                permissionLauncher.launch(UpdateInstaller.permissionIntent(context))
            } catch (error: Exception) {
                pendingInstallPath = null
                installError = error.message ?: "Не удалось открыть разрешение на установку"
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().testTag("update_section"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Приложение", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Версия ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { manager.check(baseUrl) },
                enabled = state.phase != UpdatePhase.Checking && state.phase != UpdatePhase.Downloading,
                modifier = Modifier.testTag("update_check"),
            ) { Text("Проверить") }
        }
        val status = when (state.phase) {
            UpdatePhase.Idle -> null
            UpdatePhase.Checking -> "Проверка…"
            UpdatePhase.Current -> "Установлена последняя версия"
            UpdatePhase.Available -> state.release?.let { "Доступна версия ${it.versionName}" }
            UpdatePhase.Downloading -> "Скачивание…"
            UpdatePhase.Ready -> state.release?.let { "Готово к установке: ${it.versionName}" }
            UpdatePhase.Error -> state.message ?: "Не удалось проверить обновления"
        }
        installError?.let {
            Text(it, modifier = Modifier.testTag("update_install_error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        status?.let {
            Text(
                it,
                modifier = Modifier.testTag("update_status"),
                color = if (state.phase == UpdatePhase.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.phase == UpdatePhase.Downloading) {
            val progress = if (state.totalBytes > 0) state.downloadedBytes.toFloat() / state.totalBytes else null
            LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().testTag("update_download_progress"))
            if (state.totalBytes > 0) Text("${formatBytes(state.downloadedBytes)} из ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.phase) {
                UpdatePhase.Available -> state.release?.let { release ->
                    Button(onClick = { manager.download() }, modifier = Modifier.testTag("update_download")) { Text("Скачать ${release.versionName}") }
                }
                UpdatePhase.Error -> OutlinedButton(onClick = { manager.check(baseUrl) }, modifier = Modifier.testTag("update_retry")) { Text("Повторить") }
                UpdatePhase.Ready -> state.apkFile?.let { file ->
                    Button(onClick = { requestInstall(file) }, modifier = Modifier.testTag("update_install")) { Text("Установить") }
                }
                else -> Unit
            }
        }
    }
}

private fun launchInstaller(context: android.content.Context, file: File): String? {
    try {
        val intent = UpdateInstaller.installIntent(context, file)
        context.startActivity(intent)
        return null
    } catch (_: ActivityNotFoundException) { // Keep Ready APK available for retry.
        return "Не найден установщик пакетов"
    } catch (error: Exception) { // Keep Ready APK available for retry.
        return error.message ?: "Не удалось открыть установщик"
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f МБ".format(value / (1024f * 1024f))
    value >= 1024 -> "%.0f КБ".format(value / 1024f)
    else -> "$value Б"
}
