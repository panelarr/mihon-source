// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.time.Duration

/**
 * Downloads files for the service itself (extension files, store indexes). Extensions
 * never use this client; they get their own per-source clients.
 */
class Downloader(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = "mihon-source",
) {
    class DownloadException(
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    /** Fetches [url] into memory, failing when it's larger than [maxBytes]. */
    suspend fun fetch(
        url: String,
        maxBytes: Long,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(validate(url))
                    .header("User-Agent", userAgent)
                    .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw DownloadException("GET $url failed with HTTP ${response.code}")
                    val body = response.body
                    val declared = body.contentLength()
                    if (declared > maxBytes) throw DownloadException("$url is too large ($declared bytes, limit $maxBytes)")
                    body.byteStream().use { input -> readLimited(input, maxBytes, url) }
                }
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException("GET $url failed: ${e.message}", e)
            }
        }

    private fun readLimited(
        input: java.io.InputStream,
        maxBytes: Long,
        url: String,
    ): ByteArray {
        val bytes = input.readNBytes((maxBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (bytes.size > maxBytes) throw DownloadException("$url is too large (limit $maxBytes bytes)")
        return bytes
    }

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(60)

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(TIMEOUT)
                .callTimeout(Duration.ofMinutes(5))
                .build()

        /** Accepts absolute http(s) URLs only. */
        fun validate(url: String): String {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            require(uri != null && uri.isAbsolute && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
                "'$url' is not an absolute http(s) URL"
            }
            return uri.toString()
        }
    }
}
