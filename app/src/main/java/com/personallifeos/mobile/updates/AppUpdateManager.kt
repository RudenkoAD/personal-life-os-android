package com.personallifeos.mobile.updates

import android.content.Context
import android.os.Build
import com.personallifeos.mobile.BuildConfig
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AppUpdateManager internal constructor(
    context: Context,
    private val transport: ReleaseTransport = UpdateTransport(),
    private val verifier: ReleaseVerifier = UpdateVerifier(context.applicationContext),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val operation = Mutex()
    private val origin = AtomicReference<URI?>(null)
    private val checked = AtomicReference<AppRelease?>(null)
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state

    fun check(baseUrl: String) {
        scope.launch {
            operation.withLock {
                _state.value = AppUpdateState(phase = UpdatePhase.Checking)
                try {
                    val uri = validateOrigin(baseUrl)
                    val release = Json.decodeFromString<AppRelease>(transport.manifest(uri).decodeToString())
                    validateRelease(uri, release)
                    origin.set(uri)
                    checked.set(release)
                    if (release.versionCode <= installedVersionCode()) {
                        _state.value = AppUpdateState(UpdatePhase.Current, release = release)
                    } else {
                        _state.value = AppUpdateState(UpdatePhase.Available, release = release, totalBytes = release.sizeBytes)
                    }
                } catch (_: Exception) {
                    origin.set(null); checked.set(null)
                    _state.value = AppUpdateState(phase = UpdatePhase.Error, message = "Не удалось проверить обновление")
                }
            }
        }
    }

    fun download() {
        scope.launch {
            operation.withLock {
                if (_state.value.phase !in setOf(UpdatePhase.Available, UpdatePhase.Error)) return@withLock
                val release = checked.get() ?: return@withLock
                val base = origin.get() ?: return@withLock
                if (release.versionCode <= installedVersionCode()) return@withLock
                val dir = File(appContext.filesDir, "app-updates").apply { mkdirs() }
                val tmp = File(dir, "update-${release.versionCode}.apk.part")
                val final = File(dir, "update-${release.versionCode}.apk")
                try {
                    tmp.delete()
                    _state.value = AppUpdateState(UpdatePhase.Downloading, release, 0, release.sizeBytes)
                    tmp.outputStream().use { output ->
                        transport.apk(base, release, { bytes -> _state.value = _state.value.copy(downloadedBytes = bytes) }) { bytes, count ->
                            output.write(bytes, 0, count)
                        }
                    }
                    verifier.verify(release, tmp)
                    if (final.exists()) final.delete()
                    check(tmp.renameTo(final)) { "Не удалось сохранить APK" }
                    dir.listFiles()?.filter {
                        it != final && it.name.matches(Regex("update-[0-9]+\\.apk(?:\\.part)?"))
                    }?.forEach { it.delete() }
                    _state.value = AppUpdateState(UpdatePhase.Ready, release, release.sizeBytes, release.sizeBytes, final)
                } catch (_: Exception) {
                    tmp.delete(); final.delete()
                    _state.value = AppUpdateState(UpdatePhase.Error, release = release, message = "Не удалось скачать обновление")
                }
            }
        }
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(phase = UpdatePhase.Error, message = message.take(160))
    }

    private fun validateOrigin(value: String): URI {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", true) ||
            (BuildConfig.DEBUG && uri.scheme.equals("http", true) && isLoopback(uri.host)))
        require(uri.userInfo == null)
        require(uri.rawQuery == null && uri.rawFragment == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        require(!uri.host.isNullOrEmpty())
        return URI(uri.scheme.lowercase(Locale.US), uri.userInfo, uri.host, uri.port, "/", null, null)
    }

    private fun validateRelease(origin: URI, release: AppRelease) {
        require(release.schemaVersion == 1 && release.versionCode >= 0 && release.sizeBytes in 1..APK_MAX)
        require(release.versionName.length in 1..64)
        require(release.minSdk >= 1 && release.minSdk <= Build.VERSION.SDK_INT)
        require(release.sha256.matches(Regex("[0-9a-fA-F]{64}")))
        val path = URI(release.apkPath)
        require(path.isAbsolute.not() && path.rawQuery == null && path.rawFragment == null && path.userInfo == null)
        require(path.path.startsWith("/android/releases/") && !path.path.contains(".."))
        require(origin.resolve(path).host == origin.host && origin.resolve(path).port == origin.port)
    }

    private fun installedVersionCode(): Long {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun isLoopback(host: String?): Boolean = host == "127.0.0.1" || host == "localhost" || host == "::1"

    companion object {
        private const val APK_MAX = 150L * 1024 * 1024
        @Volatile private var instance: AppUpdateManager? = null
        fun get(context: Context): AppUpdateManager = instance ?: synchronized(this) {
            instance ?: AppUpdateManager(context).also { instance = it }
        }
    }
}
