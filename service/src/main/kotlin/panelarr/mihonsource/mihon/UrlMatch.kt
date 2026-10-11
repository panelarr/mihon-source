// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Loose URL comparisons for resolving pasted links. */
object UrlMatch {
    /** Whether [url] is on the same site as [homeUrl] (ignoring scheme and "www."). */
    fun sameSite(
        homeUrl: String?,
        url: HttpUrl,
    ): Boolean {
        val home = homeUrl?.toHttpUrlOrNull() ?: return false
        return host(home) == host(url)
    }

    /** Whether two URLs point at the same page: same site, path (ignoring a trailing slash) and query. */
    fun same(
        a: String,
        b: String,
    ): Boolean {
        val left = a.toHttpUrlOrNull() ?: return false
        val right = b.toHttpUrlOrNull() ?: return false
        return host(left) == host(right) &&
            left.encodedPath.trimEnd('/') == right.encodedPath.trimEnd('/') &&
            left.query == right.query
    }

    private fun host(url: HttpUrl) = url.host.lowercase().removePrefix("www.")
}
