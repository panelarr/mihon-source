// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Path

/**
 * A cookie jar kept in memory and mirrored to a JSON file, so logins and clearance cookies
 * survive restarts. One jar per source (or per named shared jar).
 */
class FileCookieJar(
    file: Path,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieJar {
    private val store = JsonFile(file, ListSerializer(StoredCookie.serializer()), private = true)
    private val cookies: MutableList<Cookie> =
        store
            .read()
            .orEmpty()
            .mapNotNull { it.toCookie() }
            .filter { it.expiresAt > now() }
            .toMutableList()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        if (cookies.isEmpty()) return
        cookies.forEach { cookie ->
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now()) this.cookies += cookie
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val time = now()
        if (cookies.removeAll { it.expiresAt <= time }) persist()
        return cookies.filter { it.matches(url) }
    }

    /** All cookies that would be sent to [url]. */
    @Synchronized
    fun get(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) && it.expiresAt > now() }

    /** Removes cookies for [url]'s host, optionally only those named in [names]. Returns how many. */
    @Synchronized
    fun remove(
        url: HttpUrl,
        names: Collection<String>? = null,
    ): Int {
        val before = cookies.size
        cookies.removeAll { cookie ->
            (cookie.domain == url.host || url.host.endsWith(".${cookie.domain}")) && (names == null || cookie.name in names)
        }
        val removed = before - cookies.size
        if (removed > 0) persist()
        return removed
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        persist()
    }

    private fun persist() = store.write(cookies.map(StoredCookie::of))

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
        val persistent: Boolean,
    ) {
        fun toCookie(): Cookie? =
            runCatching {
                Cookie
                    .Builder()
                    .name(name)
                    .value(value)
                    .expiresAt(expiresAt)
                    .path(path)
                    .apply {
                        if (hostOnly) hostOnlyDomain(domain) else domain(domain)
                        if (secure) secure()
                        if (httpOnly) httpOnly()
                    }.build()
            }.getOrNull()

        companion object {
            fun of(cookie: Cookie) =
                StoredCookie(
                    name = cookie.name,
                    value = cookie.value,
                    expiresAt = cookie.expiresAt,
                    domain = cookie.domain,
                    path = cookie.path,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                    persistent = cookie.persistent,
                )
        }
    }
}
