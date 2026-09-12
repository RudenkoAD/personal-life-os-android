package com.personallifeos.mobile.updates

import java.io.IOException
import java.net.URI
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class UpdateTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: UpdateTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = UpdateTransport()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun manifestUsesFixedEndpointAndReadsBody() {
        server.enqueue(MockResponse().setBody("{}"))
        val result = transport.manifest(origin())
        assertArrayEquals("{}".encodeToByteArray(), result)
        assertEqualsPath("/android/latest.json", server.takeRequest().path)
    }

    @Test
    fun redirectAnd404AreRejected() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/elsewhere"))
        assertThrows(IOException::class.java) { transport.manifest(origin()) }
        server.enqueue(MockResponse().setResponseCode(404))
        assertThrows(IOException::class.java) { transport.manifest(origin()) }
    }

    @Test
    fun oversizedManifestAndApkAreRejectedFromContentLength() {
        server.enqueue(MockResponse().addHeader("Content-Length", (32 * 1024 + 1).toString()))
        assertThrows(IOException::class.java) { transport.manifest(origin()) }
        server.enqueue(MockResponse().addHeader("Content-Length", (150L * 1024 * 1024 + 1).toString()))
        val release = AppRelease(1, 3, "0.3", "/android/releases/3/a.apk", "a".repeat(64), 1, 1)
        assertThrows(IOException::class.java) { transport.apk(origin(), release, {}, { _, _ -> }) }
    }

    private fun origin(): URI = URI(server.url("/").toString().removeSuffix("/"))
    private fun assertEqualsPath(expected: String, actual: String?) = assertEquals(expected, actual)
}
