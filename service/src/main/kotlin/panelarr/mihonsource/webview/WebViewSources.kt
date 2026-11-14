// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import panelarr.mihonsource.network.EffectiveProfile
import panelarr.mihonsource.network.FileCookieJar
import java.util.concurrent.ConcurrentHashMap

/** What a source's WebViews take from its network profile, looked up when a view opens a page. */
data class WebViewSourceSettings(
    /** The profile's proxy, or null for a direct connection. */
    val proxy: EffectiveProfile.Proxy?,
    /** The user agent the source's HTTP client sends to a host, used unless the extension sets its own. */
    val userAgent: (host: String) -> String,
    /** The source's cookie jar (its own, or the shared one its profile names). */
    val jar: FileCookieJar,
)

/** Finds the settings of a source's WebViews. */
fun interface WebViewSources {
    fun settings(sourceId: Long): WebViewSourceSettings
}

/**
 * Remembers when a source's WebView last found the browser unreachable, so a failed call can
 * be told apart from the site's own failure and reported as `WEBVIEW_UNAVAILABLE`.
 */
class WebViewOutages {
    private class Outage(
        val atNanos: Long,
        val message: String,
    )

    private val outages = ConcurrentHashMap<Long, Outage>()

    fun record(
        sourceId: Long,
        message: String,
    ) {
        outages[sourceId] = Outage(System.nanoTime(), message)
    }

    /** Why [sourceId]'s WebView couldn't reach the browser at or after [sinceNanos] (`System.nanoTime`), or null. */
    fun since(
        sourceId: Long,
        sinceNanos: Long,
    ): String? = outages[sourceId]?.takeIf { it.atNanos - sinceNanos >= 0 }?.message
}

/**
 * Moves cookies between a source's cookie jar and its browser context. Before a load the
 * whole jar goes into the context, so redirects to the source's other hosts carry their
 * cookies too; after it, the context's cookies come back, and the jar is written only when
 * something changed. Values are never logged.
 */
internal class JarCookieSync(
    private val jar: FileCookieJar,
) {
    private val log = LoggerFactory.getLogger(JarCookieSync::class.java)

    /** Puts the jar's cookies into [page]'s context. [url] is the page about to load. */
    suspend fun push(
        page: CdpPage,
        url: String,
    ) {
        val contextId = page.browserContextId ?: return
        val cookies = jar.all().map { toParam(it, url) }
        if (cookies.isEmpty()) return
        page.sendToBrowser(
            "Storage.setCookies",
            buildJsonObject {
                put("cookies", JsonArray(cookies))
                put("browserContextId", JsonPrimitive(contextId))
            },
        )
    }

    /** Copies the cookies of [page]'s context into the jar. */
    suspend fun pull(page: CdpPage) {
        val contextId = page.browserContextId ?: return
        if (!page.isOpen) return
        try {
            val result = page.sendToBrowser("Storage.getCookies", buildJsonObject { put("browserContextId", JsonPrimitive(contextId)) })
            val cookies = result["cookies"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.let(::fromCdp) }.orEmpty()
            if (cookies.isNotEmpty()) jar.merge(cookies)
        } catch (e: CdpProtocolException) {
            log.debug("Couldn't read the browser context's cookies: {}", e.message)
        }
    }

    /** [pull], where a failure only means the cookies come back with a later pull. */
    suspend fun pullQuietly(page: CdpPage) {
        try {
            pull(page)
        } catch (e: WebViewException) {
            log.debug("Couldn't copy the page's cookies: {}", e.message)
        }
    }

    companion object {
        private const val MILLIS = 1000.0

        /**
         * A jar cookie as a CDP `CookieParam`. The URL scopes it like a response from that URL
         * would: the page's own origin when the cookie belongs to its host, so the browser sees
         * the same scheme and port, else the cookie's domain.
         */
        fun toParam(
            cookie: Cookie,
            pageUrl: String,
        ): JsonObject {
            val page = pageUrl.toHttpUrlOrNull()
            val scheme = if (cookie.secure) "https" else "http"
            val url =
                if (page != null && cookie.matchesHost(page.host)) {
                    page
                        .newBuilder()
                        .encodedPath(cookie.path)
                        .query(null)
                        .fragment(null)
                        .build()
                        .toString()
                } else {
                    "$scheme://${cookie.domain}${cookie.path}"
                }
            return buildJsonObject {
                put("name", JsonPrimitive(cookie.name))
                put("value", JsonPrimitive(cookie.value))
                put("url", JsonPrimitive(url))
                // Without a domain the browser keeps it for the URL's host only, as a host-only cookie is.
                if (!cookie.hostOnly) put("domain", JsonPrimitive(".${cookie.domain}"))
                put("path", JsonPrimitive(cookie.path))
                put("secure", JsonPrimitive(cookie.secure))
                put("httpOnly", JsonPrimitive(cookie.httpOnly))
                if (cookie.persistent) put("expires", JsonPrimitive(cookie.expiresAt / MILLIS))
            }
        }

        private fun Cookie.matchesHost(host: String): Boolean =
            if (hostOnly) {
                host == domain
            } else {
                host == domain ||
                    host.endsWith(".$domain")
            }

        /** A CDP `Cookie` as the jar keeps it, or null when it can't be one (an invalid domain, say). */
        fun fromCdp(cookie: JsonObject): Cookie? {
            val name = cookie.string("name") ?: return null
            val value = cookie.string("value") ?: return null
            val rawDomain = cookie.string("domain")?.takeIf { it.isNotEmpty() } ?: return null
            // A leading dot marks a domain cookie; without one it's for the host only.
            val hostOnly = !rawDomain.startsWith(".")
            val domain = rawDomain.removePrefix(".")
            return runCatching {
                Cookie
                    .Builder()
                    .name(name)
                    .value(value)
                    .path(cookie.string("path") ?: "/")
                    .apply {
                        if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                        val session = (cookie["session"] as? JsonPrimitive)?.booleanOrNull ?: true
                        val expires = (cookie["expires"] as? JsonPrimitive)?.doubleOrNull
                        if (!session && expires != null && expires > 0) expiresAt((expires * MILLIS).toLong())
                        if ((cookie["secure"] as? JsonPrimitive)?.booleanOrNull == true) secure()
                        if ((cookie["httpOnly"] as? JsonPrimitive)?.booleanOrNull == true) httpOnly()
                    }.build()
            }.getOrNull()
        }
    }
}

/**
 * Answers a proxy's authentication challenges (`Fetch.authRequired`) with a profile's
 * credentials, once per request: asked again means they were refused, so it gives up rather
 * than loop. Challenges from anything but the proxy are cancelled, as Android does.
 */
internal class ProxyAuth(
    private val credentials: Pair<String, String>,
) {
    private val log = LoggerFactory.getLogger(ProxyAuth::class.java)
    private val answered = ConcurrentHashMap.newKeySet<String>()

    suspend fun answer(
        page: CdpPage,
        params: JsonObject,
    ) {
        val id = params.string("requestId") ?: return
        val fromProxy = (params["authChallenge"] as? JsonObject)?.string("source") == "Proxy"
        val provide = fromProxy && answered.add(id)
        try {
            page.send(
                "Fetch.continueWithAuth",
                buildJsonObject {
                    put("requestId", JsonPrimitive(id))
                    put(
                        "authChallengeResponse",
                        buildJsonObject {
                            put("response", JsonPrimitive(if (provide) "ProvideCredentials" else "CancelAuth"))
                            if (provide) {
                                put("username", JsonPrimitive(credentials.first))
                                put("password", JsonPrimitive(credentials.second))
                            }
                        },
                    )
                },
            )
        } catch (e: WebViewException) {
            log.debug("Couldn't answer an authentication request: {}", e.message)
        }
        if (fromProxy && !provide) log.warn("The proxy refused the credentials of a source's profile")
    }
}
