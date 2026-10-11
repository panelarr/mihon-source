// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import java.util.concurrent.TimeUnit

/**
 * A token bucket: [requests] per [periodNanos], plus up to [burst] saved-up requests.
 * [acquire] blocks until a request may go, like the rate limiting extensions do
 * themselves (requests run on OkHttp's threads, so blocking is fine).
 */
class RateLimiter(
    private val requests: Int,
    private val periodNanos: Long,
    private val burst: Int = 0,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { TimeUnit.NANOSECONDS.sleep(it) },
) {
    private val capacity = (requests + burst).toDouble()
    private val nanosPerToken = periodNanos.toDouble() / requests
    private var tokens = capacity
    private var last = nanoTime()

    init {
        require(requests > 0 && periodNanos > 0 && burst >= 0) { "invalid rate limit" }
    }

    /** Waits for a token. Returns the nanoseconds waited. */
    fun acquire(): Long {
        val wait: Long
        synchronized(this) {
            refill()
            if (tokens >= 1.0) {
                tokens -= 1.0
                return 0
            }
            wait = ((1.0 - tokens) * nanosPerToken).toLong()
            // Reserve the token now so concurrent callers queue up behind this one.
            tokens -= 1.0
        }
        sleep(wait)
        return wait
    }

    private fun refill() {
        val now = nanoTime()
        tokens = (tokens + (now - last) / nanosPerToken).coerceAtMost(capacity)
        last = now
    }

    fun matches(settings: RateLimitSettings): Boolean =
        settings.requests == requests && TimeUnit.MILLISECONDS.toNanos(settings.periodMillis) == periodNanos && settings.burst == burst

    companion object {
        fun of(settings: RateLimitSettings) =
            RateLimiter(settings.requests, TimeUnit.MILLISECONDS.toNanos(settings.periodMillis), settings.burst)
    }
}
