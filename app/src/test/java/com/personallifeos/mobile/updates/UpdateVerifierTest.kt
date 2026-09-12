package com.personallifeos.mobile.updates

import android.app.Application
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class UpdateVerifierTest {
    private lateinit var file: File
    private lateinit var verifier: UpdateVerifier

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        file = File(context.filesDir, "update-verifier-test.apk")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        verifier = UpdateVerifier(context)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun rejectsExactSizeMismatchBeforeParsingCandidate() {
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(release(sizeBytes = 3), file)
        }
    }

    @Test
    fun rejectsShaMismatchBeforeParsingCandidate() {
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(release(sha256 = "0".repeat(64)), file)
        }
    }

    private fun release(
        sizeBytes: Long = file.length(),
        sha256: String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) },
    ) = AppRelease(1, 3, "0.3.0", "/android/releases/3/a.apk", sha256, sizeBytes, 1)
}
