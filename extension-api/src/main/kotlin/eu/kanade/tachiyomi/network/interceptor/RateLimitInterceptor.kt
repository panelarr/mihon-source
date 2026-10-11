// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Semaphore

/**
 * A sliding window of [permits] requests per [periodMillis], for one host (or all hosts
 * when [host] is null). Responses served from cache give their slot back.
 */
internal class RateLimitInterceptor(
    private val host: String?,
    private val permits: Int,
    private val periodMillis: Long,
) : Interceptor {
    private val requests = ArrayDeque<Long>(permits)
    private val fairLock = Semaphore(1, true)

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")
        val request = chain.request()
        if (host != null && request.url.host != host) return chain.proceed(request)

        val slot: Long
        try {
            fairLock.acquire()
        } catch (e: InterruptedException) {
            throw IOException(e)
        }
        try {
            synchronized(requests) {
                while (requests.size >= permits) {
                    val now = System.nanoTime() / NANOS_PER_MILLI
                    val waitMillis = requests.first + periodMillis - now
                    if (waitMillis <= 0) {
                        requests.removeFirst()
                        continue
                    }
                    (requests as Object).wait(waitMillis)
                    if (call.isCanceled()) throw IOException("Canceled")
                }
                slot = System.nanoTime() / NANOS_PER_MILLI
                requests.addLast(slot)
            }
        } finally {
            fairLock.release()
        }

        val response = chain.proceed(request)
        if (response.networkResponse == null) {
            synchronized(requests) {
                requests.remove(slot)
                (requests as Object).notifyAll()
            }
        }
        return response
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
