// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import panelarr.mihonsource.compat.WebCookieStore

/**
 * `android.webkit.CookieManager` for one source, kept in that source's cookie jar: as in
 * Mihon on Android, what an extension stores through the manager is sent by its HTTP client,
 * and what the client received is visible through the manager. [jar] is looked up on every
 * call, as the source's profile may switch it to a shared jar.
 */
class JarCookieStore(
    private val jar: () -> FileCookieJar,
) : WebCookieStore {
    override fun setCookie(
        url: String,
        setCookie: String,
    ): Boolean {
        val parsed = httpUrl(url) ?: return false
        val cookie = Cookie.parse(parsed, setCookie) ?: return false
        jar().merge(listOf(cookie))
        return true
    }

    override fun getCookie(url: String): String? {
        val parsed = httpUrl(url) ?: return null
        return jar()
            .get(parsed)
            .joinToString("; ") { "${it.name}=${it.value}" }
            .ifEmpty { null }
    }

    override fun removeSessionCookies(): Boolean = jar().removeIf { !it.persistent }

    override fun removeAllCookies(): Boolean = jar().removeIf { true }

    override fun removeExpiredCookies() {
        val now = System.currentTimeMillis()
        jar().removeIf { it.expiresAt <= now }
    }

    override fun hasCookies(): Boolean = jar().all().isNotEmpty()

    private companion object {
        /** Android accepts a bare host as the URL too. */
        fun httpUrl(url: String): HttpUrl? = (if ("://" in url) url else "http://$url").toHttpUrlOrNull()
    }
}
