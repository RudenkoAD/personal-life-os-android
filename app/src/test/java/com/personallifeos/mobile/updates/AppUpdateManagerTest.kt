package com.personallifeos.mobile.updates

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class AppUpdateManagerTest {
    private lateinit var context: Context
    private lateinit var transport: FakeTransport
    private lateinit var verifier: FakeVerifier

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "app-updates").deleteRecursively()
        transport = FakeTransport()
        verifier = FakeVerifier()
    }

    @Test
    fun currentReleaseIsReported() = runTest {
        val manager = manager(this)
        transport.manifest = release(versionCode = installedVersion()).json()
        manager.check("https://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Current, manager.state.value.phase)
    }

    @Test
    fun newerReleaseIsAvailable() = runTest {
        val manager = manager(this)
        transport.manifest = release(versionCode = installedVersion() + 1).json()
        manager.check("https://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Available, manager.state.value.phase)
    }

    @Test
    fun unsupportedReleaseAndBadJsonAreErrors() = runTest {
        val manager = manager(this)
        transport.manifest = release(minSdk = 10_000).json()
        manager.check("https://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Error, manager.state.value.phase)
        transport.manifest = "{broken".encodeToByteArray()
        manager.check("https://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Error, manager.state.value.phase)
    }

    @Test
    fun httpFailureAndRedirectAreErrors() = runTest {
        val manager = manager(this)
        transport.manifestFailure = IOException("HTTP 404")
        manager.check("https://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Error, manager.state.value.phase)
        transport.manifestFailure = IOException("redirect")
        manager.check("https://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Error, manager.state.value.phase)
    }

    @Test
    fun failedVerificationCleansPartialAndFinalFiles() = runTest {
        val manager = manager(this)
        val release = release(versionCode = installedVersion() + 1)
        transport.manifest = release.json()
        verifier.failure = IllegalArgumentException("hash")
        manager.check("https://updates.example")
        advanceUntilIdle()
        manager.download()
        advanceUntilIdle()
        assertEquals(UpdatePhase.Error, manager.state.value.phase)
        val files = File(context.filesDir, "app-updates").listFiles().orEmpty()
        assertTrue(files.isEmpty())
    }

    @Test
    fun originAndReleasePathRulesRejectUnsafeMetadata() = runTest {
        val manager = manager(this)
        transport.manifest = release(apkPath = "https://evil.example/android/releases/3/a.apk").json()
        manager.check("https://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Error, manager.state.value.phase)
        manager.check("http://updates.example")
        advanceUntilIdle()
        assertEquals(UpdatePhase.Error, manager.state.value.phase)
    }

    private fun manager(scope: TestScope): AppUpdateManager = AppUpdateManager(
        context = context,
        transport = transport,
        verifier = verifier,
        scope = scope,
    )

    private fun installedVersion(): Long = context.packageManager
        .getPackageInfo(context.packageName, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }

    private fun release(
        versionCode: Long = installedVersion() + 1,
        minSdk: Int = 1,
        apkPath: String = "/android/releases/3/Life-OS-0.3.0.apk",
    ) = AppRelease(1, versionCode, "0.3.0", apkPath, "a".repeat(64), 4, minSdk)

    private fun AppRelease.json(): ByteArray = Json.encodeToString<AppRelease>(this).encodeToByteArray()

    private class FakeTransport : ReleaseTransport {
        var manifest: ByteArray = ByteArray(0)
        var manifestFailure: IOException? = null
        override fun manifest(origin: URI): ByteArray = manifestFailure?.let { throw it } ?: manifest
        override fun apk(origin: URI, release: AppRelease, onBytes: (Long) -> Unit, sink: (ByteArray, Int) -> Unit) {
            val bytes = byteArrayOf(1, 2, 3, 4)
            sink(bytes, bytes.size)
            onBytes(bytes.size.toLong())
        }
    }

    private class FakeVerifier : ReleaseVerifier {
        var failure: Exception? = null
        override fun verify(release: AppRelease, file: File) { failure?.let { throw it } }
    }
}
