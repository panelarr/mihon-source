// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Response

/** Recognizes anti-bot challenge pages and turns solver cookies into OkHttp cookies. */
object Challenges {
    private val CHALLENGE_CODES = setOf(403, 503)
    private val CHALLENGE_SERVERS = setOf("cloudflare", "cloudflare-nginx")

    fun isChallenge(response: Response): Boolean =
        response.code in CHALLENGE_CODES && response.header("Server")?.lowercase() in CHALLENGE_SERVERS

    fun toCookies(
        solution: Solution,
        url: HttpUrl,
    ): List<Cookie> =
        solution.cookies.mapNotNull { solved ->
            runCatching {
                Cookie
                    .Builder()
                    .name(solved.name)
                    .value(solved.value)
                    .path(solved.path.ifEmpty { "/" })
                    .apply {
                        val domain = solved.domain.removePrefix(".").ifEmpty { url.host }
                        domain(domain)
                        solved.expires?.takeIf { it > 0 }?.let { expiresAt((it * MILLIS).toLong()) }
                        if (solved.secure) secure()
                        if (solved.httpOnly) httpOnly()
                    }.build()
            }.getOrNull()
        }

    private const val MILLIS = 1000
}

/** User agents the service hands out. */
object UserAgents {
    const val DEFAULT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val DESKTOP =
        listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
        )

    /** A stable pick per source, so a source doesn't change identity on every restart. */
    fun desktopFor(sourceId: Long): String = DESKTOP[Math.floorMod(sourceId, DESKTOP.size.toLong()).toInt()]

    fun isOurs(agent: String): Boolean = agent == DEFAULT || agent in DESKTOP
}
