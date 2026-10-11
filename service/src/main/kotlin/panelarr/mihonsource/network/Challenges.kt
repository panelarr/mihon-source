// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.Response

/** Recognizes anti-bot challenge pages and turns solver cookies into OkHttp cookies. */
object Challenges {
    private val CHALLENGE_CODES = setOf(403, 503)
    private val CHALLENGE_SERVERS = setOf("cloudflare", "cloudflare-nginx")

    /** Strings that only appear in anti-bot challenge pages. */
    private val BODY_MARKERS =
        listOf(
            "<title>just a moment...</title>",
            "challenge-platform",
            "cf-chl",
            "cf_chl_opt",
            "cf-browser-verification",
            "<title>attention required! | cloudflare</title>",
        )

    /** How much of a body is inspected; challenge pages are small. */
    private const val PEEK_BYTES = 64L * 1024

    /** Whether [response] could be a challenge at all (a 403 or 503). */
    fun isCandidate(response: Response): Boolean = response.code in CHALLENGE_CODES

    /**
     * Whether [response] is an anti-bot challenge: a 403 or 503 that says so in its
     * `cf-mitigated` header, comes from a Cloudflare server, or carries a challenge page.
     * The Server header alone isn't enough, because proxies and CDNs rewrite or drop it.
     */
    fun isChallenge(response: Response): Boolean {
        if (!isCandidate(response)) return false
        if (response.header("cf-mitigated")?.trim()?.equals("challenge", ignoreCase = true) == true) return true
        if (response.header("Server")?.trim()?.lowercase() in CHALLENGE_SERVERS) return true
        val body = runCatching { response.peekBody(PEEK_BYTES).string().lowercase() }.getOrNull() ?: return false
        return BODY_MARKERS.any { it in body }
    }

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
