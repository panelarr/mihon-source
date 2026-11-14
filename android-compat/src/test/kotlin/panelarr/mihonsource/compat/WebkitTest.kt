// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebkitTest {
    @AfterEach
    fun tearDown() {
        AndroidHost.webViewProvider = null
        AndroidHost.cookieListener = null
        AndroidHost.cookieStores = null
        CookieManager.getInstance().removeAllCookies(null)
    }

    @Test
    fun `without a browser, constructing a WebView fails with a hint`() {
        val error = assertFailsWith<UnsupportedOperationException> { WebView(AndroidHost.application) }
        assertEquals(WebView.MESSAGE_UNSUPPORTED, error.message)
        assertTrue("MIHON_WEBVIEW_URL" in error.message!!)
    }

    /** Records what the WebView passes on. */
    private class RecordingEngine : WebViewEngine {
        val calls = mutableListOf<String>()

        override fun loadUrl(
            url: String,
            headers: Map<String, String>,
        ) {
            calls += "load $url $headers"
        }

        override fun loadDataWithBaseURL(
            baseUrl: String?,
            data: String,
            mimeType: String?,
            encoding: String?,
            historyUrl: String?,
        ) {
            calls += "data $baseUrl $data $mimeType"
        }

        override fun evaluateJavascript(
            script: String,
            callback: ValueCallback<String>?,
        ) {
            calls += "eval $script"
            callback?.onReceiveValue("42")
        }

        override fun addJavascriptInterface(
            target: Any,
            name: String,
        ) {
            calls += "add $name"
        }

        override fun removeJavascriptInterface(name: String) {
            calls += "remove $name"
        }

        override fun stopLoading() {
            calls += "stop"
        }

        override fun reload() {
            calls += "reload"
        }

        override fun destroy() {
            calls += "destroy"
        }

        override val url: String = "https://example.org/now"
        override val originalUrl: String = "https://example.org/"
        override val title: String = "Now"
        override val progress: Int = 100
    }

    @Test
    fun `with a browser, a WebView passes its calls to the engine`() {
        val engine = RecordingEngine()
        var created: WebView? = null
        AndroidHost.webViewProvider =
            WebViewProvider { view ->
                created = view
                engine
            }
        val view = WebView(AndroidHost.application)
        assertSame(view, created)
        assertFalse(view.settings.javaScriptEnabled, "JavaScript is off by default, as on Android")
        assertTrue(view.settings.userAgentString.contains("Android"))
        assertEquals(WebViewClient::class.java, view.webViewClient.javaClass)

        view.loadUrl("https://example.org/a")
        view.loadUrl("https://example.org/b", mapOf("X-A" to "1"))
        view.loadData("<p>hi</p>", "text/html", null)
        view.loadData("PHA+aGk8L3A+", null, "base64")
        view.loadDataWithBaseURL("https://example.org/", "<p>x</p>", "text/html", "utf-8", null)
        var result: String? = null
        view.evaluateJavascript("6 * 7") { result = it }
        view.addJavascriptInterface(Any(), "bridge")
        view.removeJavascriptInterface("bridge")
        view.stopLoading()
        view.reload()
        view.destroy()
        assertEquals("42", result)
        assertEquals(
            listOf(
                "load https://example.org/a {}",
                "load https://example.org/b {X-A=1}",
                "load data:text/html,<p>hi</p> {}",
                "load data:text/html;base64,PHA+aGk8L3A+ {}",
                "data https://example.org/ <p>x</p> text/html",
                "eval 6 * 7",
                "add bridge",
                "remove bridge",
                "stop",
                "reload",
                "destroy",
            ),
            engine.calls,
        )
        assertEquals("https://example.org/now", view.url)
        assertEquals("Now", view.title)
        assertEquals(100, view.progress)
    }

    @Test
    fun `newer client overloads call the deprecated ones by default, as on Android`() {
        val seen = mutableListOf<String>()
        val client =
            object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    url: String,
                ): Boolean {
                    seen += "override $url"
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldInterceptRequest(
                    view: WebView,
                    url: String,
                ): WebResourceResponse? {
                    seen += "intercept $url"
                    return null
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String,
                    failingUrl: String,
                ) {
                    seen += "error $errorCode $description $failingUrl"
                }
            }
        AndroidHost.webViewProvider = WebViewProvider { RecordingEngine() }
        val view = WebView(AndroidHost.application)
        val request = request("https://example.org/x", mainFrame = true)
        assertTrue(client.shouldOverrideUrlLoading(view, request))
        assertNull(client.shouldInterceptRequest(view, request))
        val error =
            object : WebResourceError() {
                override fun getErrorCode() = WebViewClient.ERROR_HOST_LOOKUP

                override fun getDescription() = "net::ERR_NAME_NOT_RESOLVED"
            }
        client.onReceivedError(view, request, error)
        client.onReceivedError(view, request("https://example.org/img.png", mainFrame = false), error)
        assertEquals(
            listOf(
                "override https://example.org/x",
                "intercept https://example.org/x",
                "error -2 net::ERR_NAME_NOT_RESOLVED https://example.org/x",
            ),
            seen,
        )
    }

    private fun request(
        url: String,
        mainFrame: Boolean,
    ) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame() = mainFrame

        override fun isRedirect() = false

        override fun hasGesture() = false

        override fun getMethod() = "GET"

        override fun getRequestHeaders() = emptyMap<String, String>()
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

    /** A source's own store, recording what reaches it. */
    private class RecordingStore : WebCookieStore {
        val cookies = mutableMapOf<String, String>()

        override fun setCookie(
            url: String,
            setCookie: String,
        ): Boolean {
            cookies[setCookie.substringBefore('=')] = setCookie.substringAfter('=').substringBefore(';')
            return true
        }

        override fun getCookie(url: String): String? = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }

        override fun removeSessionCookies(): Boolean = removeAllCookies()

        override fun removeAllCookies(): Boolean = cookies.isNotEmpty().also { cookies.clear() }

        override fun removeExpiredCookies() = Unit

        override fun hasCookies(): Boolean = cookies.isNotEmpty()
    }

    @Test
    fun `code working for a source uses that source's store, and other code the shared one`() {
        val a = RecordingStore()
        val b = RecordingStore()
        AndroidHost.cookieStores = WebCookieStores { id -> mapOf(1L to a, 2L to b)[id] }
        val manager = CookieManager.getInstance()

        SourceCaller.with(1L) { manager.setCookie("https://example.org/", "token=a") }
        SourceCaller.with(2L) { manager.setCookie("https://example.org/", "token=b") }
        manager.setCookie("https://example.org/", "token=shared")

        assertEquals("token=a", SourceCaller.with(1L) { manager.getCookie("https://example.org/") })
        assertEquals("token=b", SourceCaller.with(2L) { manager.getCookie("https://example.org/") })
        assertEquals("token=shared", manager.getCookie("https://example.org/"))
        // A source the host has no store for uses the shared one.
        assertEquals("token=shared", SourceCaller.with(3L) { manager.getCookie("https://example.org/") })

        // Clearing is per source too.
        SourceCaller.with(1L) { manager.removeAllCookies(null) }
        assertFalse(a.hasCookies())
        assertTrue(b.hasCookies())
        assertTrue(manager.hasCookies())

        // Refused cookies stay refused, whichever the store.
        manager.setAcceptCookie(false)
        try {
            SourceCaller.with(2L) { manager.setCookie("https://example.org/", "late=1") }
            assertNull(b.cookies["late"])
        } finally {
            manager.setAcceptCookie(true)
        }
    }

    @Test
    fun `cookie listener mirrors cookies`() {
        val seen = mutableListOf<Pair<String, String>>()
        AndroidHost.cookieListener = CookieListener { url, cookie -> seen += url to cookie }
        CookieManager.getInstance().setCookie("https://example.org", "a=b; Path=/")
        assertEquals(listOf("https://example.org" to "a=b; Path=/"), seen)
    }
}
