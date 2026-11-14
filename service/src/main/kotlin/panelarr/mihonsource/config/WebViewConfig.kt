// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.config

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Duration

/**
 * The optional browser service: a headless Chromium reached over the Chrome DevTools
 * Protocol, which WebView sources need. Configured only when `MIHON_WEBVIEW_URL` is set.
 */
data class WebViewConfig(
    /** `http(s)://host:port` (the endpoint is discovered) or a `ws(s)://` DevTools URL. */
    val url: String,
    /** Pages open at once, across all sources. */
    val maxPages: Int = DEFAULT_MAX_PAGES,
    /** How long one page load may take. */
    val loadTimeout: Duration = DEFAULT_LOAD_TIMEOUT,
    /** Browser contexts nobody used for this long are closed. */
    val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
) {
    companion object {
        const val DEFAULT_MAX_PAGES = 2
        val DEFAULT_LOAD_TIMEOUT: Duration = Duration.ofSeconds(60)
        val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val SCHEMES = setOf("http", "https", "ws", "wss")

        /** The settings from `MIHON_WEBVIEW_*`, or null (off) when `MIHON_WEBVIEW_URL` isn't set. */
        fun fromEnv(value: (String) -> String?): WebViewConfig? {
            val url = value("MIHON_WEBVIEW_URL") ?: return null
            val maxPages =
                value("MIHON_WEBVIEW_MAX_PAGES")?.let { raw ->
                    raw.toIntOrNull()?.takeIf { it > 0 } ?: error("MIHON_WEBVIEW_MAX_PAGES must be a number of at least 1, got '$raw'")
                }
            return WebViewConfig(
                url = validUrl(url),
                maxPages = maxPages ?: DEFAULT_MAX_PAGES,
                loadTimeout = positive("MIHON_WEBVIEW_LOAD_TIMEOUT", value("MIHON_WEBVIEW_LOAD_TIMEOUT")) ?: DEFAULT_LOAD_TIMEOUT,
                idleTimeout = positive("MIHON_WEBVIEW_IDLE_TIMEOUT", value("MIHON_WEBVIEW_IDLE_TIMEOUT")) ?: DEFAULT_IDLE_TIMEOUT,
            )
        }

        /** A duration that must be longer than zero, or null when unset. */
        private fun positive(
            name: String,
            text: String?,
        ): Duration? =
            text
                ?.let {
                    AppConfig.duration(it)
                }?.also { check(!it.isZero && !it.isNegative) { "$name must be longer than zero, got '$text'" } }

        /**
         * [url] if it's an absolute http(s) or ws(s) URL with a host. It's parsed the way the
         * browser client parses it, so names `java.net.URI` rejects, such as compose service
         * names with an underscore, are accepted.
         */
        fun validUrl(url: String): String {
            val text = url.trim()
            val scheme = text.substringBefore("://", "").lowercase()
            check(scheme in SCHEMES) { "MIHON_WEBVIEW_URL must be an http(s):// or ws(s):// URL, got scheme '$scheme'" }
            val parsed = text.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http").toHttpUrlOrNull()
            check(parsed != null && parsed.host.isNotEmpty()) {
                val host = text.substringAfter("://").substringBefore('/').substringAfterLast('@')
                "MIHON_WEBVIEW_URL has no valid host: '$host' isn't a host name, IP address or host:port"
            }
            return text.trimEnd('/')
        }
    }
}
