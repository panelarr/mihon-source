// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.net.Uri
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UriTest {
    @Test
    fun `parses a hierarchical uri`() {
        val uri = Uri.parse("https://user:pw@example.org:8443/a%20b/c/?q=1+2&q=x%26y&flag#frag%21")
        assertEquals("https", uri.scheme)
        assertEquals("user:pw@example.org:8443", uri.authority)
        assertEquals("user:pw", uri.userInfo)
        assertEquals("example.org", uri.host)
        assertEquals(8443, uri.port)
        assertEquals("/a%20b/c/", uri.encodedPath)
        assertEquals("/a b/c/", uri.path)
        assertEquals(listOf("a b", "c"), uri.pathSegments)
        assertEquals("c", uri.lastPathSegment)
        assertEquals("q=1+2&q=x%26y&flag", uri.encodedQuery)
        assertEquals("q=1+2&q=x&y&flag", uri.query)
        assertEquals("1 2", uri.getQueryParameter("q"))
        assertEquals(listOf("1+2", "x&y"), uri.getQueryParameters("q"))
        assertEquals("", uri.getQueryParameter("flag"))
        assertNull(uri.getQueryParameter("missing"))
        assertEquals(setOf("q", "flag"), uri.queryParameterNames)
        assertEquals("frag!", uri.fragment)
        assertTrue(uri.isHierarchical)
        assertTrue(uri.isAbsolute)
        assertEquals("https://user:pw@example.org:8443/a%20b/c/?q=1+2&q=x%26y&flag#frag%21", uri.toString())
    }

    @Test
    fun `handles relative, opaque and empty uris`() {
        val relative = Uri.parse("/path/to?x=1")
        assertTrue(relative.isRelative)
        assertNull(relative.scheme)
        assertNull(relative.host)
        assertEquals(-1, relative.port)
        assertEquals("/path/to", relative.path)

        val mail = Uri.parse("mailto:someone@example.org")
        assertTrue(mail.isOpaque)
        assertEquals("someone@example.org", mail.schemeSpecificPart)
        assertNull(mail.path)

        assertEquals("", Uri.EMPTY.toString())
        assertEquals(emptyList(), Uri.EMPTY.pathSegments)
        assertEquals("mailto:a%40b.c#x%20y", Uri.fromParts("mailto", "a@b.c", "x y").toString())
    }

    @Test
    fun `encode keeps android's unreserved set`() {
        assertEquals("aZ09_-!.~'()*", Uri.encode("aZ09_-!.~'()*"))
        assertEquals("a%20b%2Fc%3F%26%3D%2B%C3%A9%F0%9F%98%80", Uri.encode("a b/c?&=+é😀"))
        assertEquals("a%20b/c", Uri.encode("a b/c", "/"))
        assertEquals("a b/c?&=+é😀", Uri.decode("a%20b%2Fc%3F%26%3D%2B%C3%A9%F0%9F%98%80"))
        assertEquals("a+b%zz%", Uri.decode("a+b%zz%"))
        assertNull(Uri.encode(null))
    }

    @Test
    fun `builds uris`() {
        val built =
            Uri
                .Builder()
                .scheme("https")
                .authority("example.org")
                .appendPath("search results")
                .appendEncodedPath("raw%2Fpart")
                .appendQueryParameter("q", "a&b c")
                .appendQueryParameter("page", "2")
                .fragment("top")
                .build()
        assertEquals("https://example.org/search%20results/raw%2Fpart?q=a%26b%20c&page=2#top", built.toString())
        assertEquals("a&b c", built.getQueryParameter("q"))

        val upon =
            Uri
                .parse("https://example.org/api?x=1")
                .buildUpon()
                .appendPath("v2")
                .appendQueryParameter("y", "2")
        assertEquals("https://example.org/api/v2?x=1&y=2", upon.toString())

        assertEquals("https://example.org/a/b", Uri.withAppendedPath(Uri.parse("https://example.org/a"), "b").toString())
        assertEquals(
            "/a/b",
            Uri
                .Builder()
                .appendPath("a")
                .appendPath("b")
                .build()
                .toString(),
        )
        assertEquals("file:///tmp/x%20y", Uri.fromFile(File("/tmp/x y")).toString())
    }

    @Test
    fun `equality is by string`() {
        assertEquals(Uri.parse("https://a/b"), Uri.parse("https://a/b"))
        assertFalse(Uri.parse("https://a/b") == Uri.parse("https://a/c"))
        assertEquals("[::1]", Uri.parse("http://[::1]:80/").host)
        assertEquals(80, Uri.parse("http://[::1]:80/").port)
    }
}
