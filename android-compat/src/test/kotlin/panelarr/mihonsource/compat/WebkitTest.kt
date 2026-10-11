// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.webkit.CookieManager
import android.webkit.WebView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebkitTest {
    @AfterEach
    fun tearDown() {
        AndroidHost.cookieListener = null
        CookieManager.getInstance().removeAllCookies(null)
    }

    @Test
    fun `constructing a WebView fails clearly`() {
        val error = assertFailsWith<UnsupportedOperationException> { WebView(AndroidHost.application) }
        assertEquals("WebView is not available in mihon-source", error.message)
    }

    @Test
    fun `cookie manager stores cookies by domain and path`() {
        val cookies = CookieManager.getInstance()
        cookies.setCookie("https://example.org/a/page", "plain=1")
        cookies.setCookie("https://example.org/", "wide=2; Domain=example.org; Path=/")
        cookies.setCookie("https://example.org/", "deep=3; Path=/only")
        cookies.setCookie("https://example.org/", "secure=4; Secure")
        cookies.setCookie("https://example.org/", "gone=5; Max-Age=0")

        assertEquals("plain=1; wide=2; secure=4", cookies.getCookie("https://example.org/a/other"))
        assertEquals("wide=2", cookies.getCookie("http://sub.example.org/"))
        assertEquals("wide=2; deep=3", cookies.getCookie("http://example.org/only/x"))
        assertNull(cookies.getCookie("https://other.test/"))

        cookies.setCookie("https://example.org/", "wide=changed; Domain=example.org; Path=/")
        assertEquals("wide=changed", cookies.getCookie("http://sub.example.org/"))
        assertTrue(cookies.hasCookies())
        cookies.flush()
        cookies.removeAllCookies(null)
        assertFalse(cookies.hasCookies())
    }

    @Test
    fun `cookie listener mirrors cookies`() {
        val seen = mutableListOf<Pair<String, String>>()
        AndroidHost.cookieListener = CookieListener { url, cookie -> seen += url to cookie }
        CookieManager.getInstance().setCookie("https://example.org", "a=b; Path=/")
        assertEquals(listOf("https://example.org" to "a=b; Path=/"), seen)
    }
}
