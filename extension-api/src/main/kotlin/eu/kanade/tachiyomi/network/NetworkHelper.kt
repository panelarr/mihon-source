// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.network

import okhttp3.CookieJar
import okhttp3.OkHttpClient

/**
 * What extensions get as `network`. In mihon-source every HTTP source gets its own
 * instance, whose client follows that source's network profile (proxy, cookies, user
 * agent, rate limit, solver). Extensions never construct it.
 */
class NetworkHelper(
    private val backend: Backend,
) {
    /** The host side of a network helper. */
    interface Backend {
        val client: OkHttpClient
        val cookieJar: CookieJar

        fun userAgent(): String
    }

    val client: OkHttpClient
        get() = backend.client

    @Deprecated("The regular client handles challenges")
    val cloudflareClient: OkHttpClient
        get() = client

    val cookieJar: CookieJar
        get() = backend.cookieJar

    fun defaultUserAgentProvider(): String = backend.userAgent()
}
