package com.personallifeos.mobile.updates

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

internal interface ReleaseTransport {
    fun manifest(origin: URI): ByteArray
    fun apk(origin: URI, release: AppRelease, onBytes: (Long) -> Unit, sink: (ByteArray, Int) -> Unit)
}

internal class UpdateTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build(),
) : ReleaseTransport {
    override fun manifest(origin: URI): ByteArray = get(origin.resolve("/android/latest.json"), MANIFEST_LIMIT)

    override fun apk(origin: URI, release: AppRelease, onBytes: (Long) -> Unit, sink: (ByteArray, Int) -> Unit) {
        val url = origin.resolve(release.apkPath)
        client.newCall(Request.Builder().url(url.toString()).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            if (body.contentLength() > APK_LIMIT || body.contentLength() > release.sizeBytes) throw IOException("APK too large")
            val source = body.source()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                total += read
                if (total > APK_LIMIT || total > release.sizeBytes) throw IOException("APK too large")
                sink(buffer, read)
                onBytes(total)
            }
        }
    }

    private fun get(url: URI, limit: Long): ByteArray {
        val call = client.newCall(Request.Builder().url(url.toString()).get().build())
        call.timeout().timeout(30, TimeUnit.SECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            if (body.contentLength() > limit) throw IOException("manifest too large")
            val source = body.source()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                total += read
                if (total > limit) throw IOException("manifest too large")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 16 * 1024
        const val MANIFEST_LIMIT = 32 * 1024L
        const val APK_LIMIT = 150L * 1024 * 1024
    }
}
