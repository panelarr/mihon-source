// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import android.graphics.Bitmap
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import panelarr.mihonsource.compat.AndroidHost
import panelarr.mihonsource.compat.WebViewProvider
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeCdp
import java.net.ServerSocket
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The WebView engine against a fake CDP browser: commands, callbacks and their threads. */
class CdpWebViewTest {
    private val fakes = mutableListOf<FakeCdp>()
    private val browsers = mutableListOf<CdpBrowser>()

    @AfterEach
    fun tearDown() {
        AndroidHost.webViewProvider = null
        browsers.forEach { it.close() }
        fakes.forEach { it.close() }
    }

    private fun fake(): FakeCdp =
        FakeCdp().also { fake ->
            fakes += fake
            // Like Chromium: the main frame commits, then the page loads.
            fake.after = { command ->
                if (command.method == "Page.navigate") {
                    val url =
                        command.params
                            .getValue("url")
                            .jsonPrimitive.content
                    listOf(
                        "Page.frameNavigated" to
                            buildJsonObject {
                                put(
                                    "frame",
                                    buildJsonObject {
                                        put("id", JsonPrimitive("frame"))
                                        put("url", JsonPrimitive(url))
                                    },
                                )
                            },
                        "Page.loadEventFired" to buildJsonObject { put("timestamp", JsonPrimitive(1)) },
                    )
                } else {
                    emptyList()
                }
            }
        }

    private fun webView(
        url: String,
        idleLimit: Duration = Duration.ofSeconds(30),
    ): WebView {
        val config = WebViewConfig(url, loadTimeout = Duration.ofSeconds(5))
        val browser = CdpBrowser(config).also { browsers += it }
        AndroidHost.webViewProvider = WebViewProvider { CdpWebView(it, browser, "test-${it.hashCode()}", config.loadTimeout, idleLimit) }
        return WebView(AndroidHost.application)
    }

    /** Records callbacks with the thread they ran on. */
    private class Recorder : WebViewClient() {
        val events = LinkedBlockingQueue<String>()
        val threads = CopyOnWriteArrayList<String>()

        private fun record(event: String) {
            threads += Thread.currentThread().name
            events += event
        }

        override fun onPageStarted(
            view: WebView,
            url: String,
            favicon: Bitmap?,
        ) = record("started $url")

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) = record("finished $url")

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) = record("error ${error.errorCode} main=${request.isForMainFrame}")

        fun next(): String = events.poll(5, TimeUnit.SECONDS) ?: fail("no callback within 5s")
    }

    private fun eventually(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) {
            if (System.nanoTime() > deadline) fail("condition not met within 5s")
            Thread.sleep(20)
        }
    }

    private fun onMain(block: () -> Unit) {
        android.os.Handler(Looper.getMainLooper()).post(block)
    }

    @Test
    fun `a load reports its page events on the main looper`() {
        val fake = fake()
        val view = webView(fake.url)
        val recorder = Recorder()
        view.webViewClient = recorder
        view.settings.userAgentString = "Test agent"
        view.loadUrl("http://site.example/a")
        assertEquals("started http://site.example/a", recorder.next())
        assertEquals("finished http://site.example/a", recorder.next())
        assertTrue(recorder.threads.all { it == "main" }, recorder.threads.toString())
        assertEquals("http://site.example/a", view.url)
        assertEquals(100, view.progress)

        val agent = fake.sent("Emulation.setUserAgentOverride").single()
        assertEquals(
            "Test agent",
            agent.params
                .getValue("userAgent")
                .jsonPrimitive.content,
        )
        // JavaScript is off until the settings say otherwise, as on Android.
        assertTrue(
            fake
                .sent("Emulation.setScriptExecutionDisabled")
                .single()
                .params
                .getValue("value")
                .jsonPrimitive.boolean,
        )
        view.settings.javaScriptEnabled = true
        view.loadUrl("http://site.example/b")
        assertEquals("started http://site.example/b", recorder.next())
        assertEquals("finished http://site.example/b", recorder.next())
        assertFalse(
            fake
                .sent("Emulation.setScriptExecutionDisabled")
                .last()
                .params
                .getValue("value")
                .jsonPrimitive.boolean,
        )
        assertEquals(1, fake.sent("Emulation.setUserAgentOverride").size, "an unchanged agent isn't sent again")
        // No interception without a client that needs it.
        assertTrue(fake.sent("Fetch.enable").isEmpty())
    }

    @Test
    fun `script results arrive as JSON on the main looper`() {
        val fake = fake()
        val view = webView(fake.url)
        val results = LinkedBlockingQueue<Pair<String, String>>()
        view.evaluateJavascript("1 + 1") { results += it to Thread.currentThread().name }
        assertEquals("\"evaluated: 1 + 1\"" to "main", results.poll(5, TimeUnit.SECONDS))

        fake.handler = { command ->
            if (command.method == "Runtime.evaluate") {
                FakeCdp.Result(buildJsonObject { put("result", buildJsonObject { put("type", JsonPrimitive("undefined")) }) })
            } else {
                null
            }
        }
        view.evaluateJavascript("undefined") { results += it to Thread.currentThread().name }
        assertEquals("null" to "main", results.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `without a reachable browser, loads fail like a network error and scripts answer null`() {
        val closed = ServerSocket(0).use { it.localPort }
        val view = webView("http://127.0.0.1:$closed")
        val recorder = Recorder()
        view.webViewClient = recorder
        view.loadUrl("http://site.example/")
        assertEquals("error ${WebViewClient.ERROR_CONNECT} main=true", recorder.next())
        assertEquals("finished http://site.example/", recorder.next())
        val result = LinkedBlockingQueue<String>()
        view.evaluateJavascript("1") { result += it }
        assertEquals("null", result.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `destroy closes the page and its context`() {
        val fake = fake()
        val view = webView(fake.url)
        val recorder = Recorder()
        view.webViewClient = recorder
        view.loadUrl("http://site.example/")
        recorder.next()
        assertEquals(1, fake.targets.size)
        assertEquals(1, fake.contexts.size)
        onMain { view.destroy() }
        eventually { fake.targets.isEmpty() && fake.contexts.isEmpty() }
    }

    @Test
    fun `an idle view gives its page back and opens a new one when used again`() {
        val fake = fake()
        val view = webView(fake.url, idleLimit = Duration.ofMillis(100))
        val results = LinkedBlockingQueue<String>()
        view.evaluateJavascript("1") { results += it }
        assertNotNull(results.poll(5, TimeUnit.SECONDS))
        eventually { fake.targets.isEmpty() }
        view.evaluateJavascript("2") { results += it }
        assertEquals("\"evaluated: 2\"", results.poll(5, TimeUnit.SECONDS))
        assertEquals(2, fake.sent("Target.createTarget").size)
    }

    @Test
    fun `paused requests are answered by the client`() {
        val fake = fake()
        val view = webView(fake.url)
        val overridden = LinkedBlockingQueue<String>()
        view.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    overridden += "${request.url} on ${Thread.currentThread().name}"
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? =
                    if (request.url.path == "/served.js") {
                        WebResourceResponse("application/javascript", "utf-8", "ok()".byteInputStream())
                    } else {
                        null
                    }
            }
        view.loadUrl("http://site.example/", mapOf("X-Extra" to "1"))
        eventually { fake.sent("Page.navigate").isNotEmpty() }
        assertEquals(1, fake.sent("Fetch.enable").size)
        val session = fake.sent("Page.navigate").single().sessionId
        val target = session!!.removePrefix("session-")

        fun paused(
            id: String,
            url: String,
            type: String,
            frame: String,
        ) = fake.emit(
            "Fetch.requestPaused",
            buildJsonObject {
                put("requestId", JsonPrimitive(id))
                put(
                    "request",
                    buildJsonObject {
                        put("url", JsonPrimitive(url))
                        put("method", JsonPrimitive("GET"))
                        put("headers", buildJsonObject { put("Accept", JsonPrimitive("*/*")) })
                    },
                )
                put("resourceType", JsonPrimitive(type))
                put("frameId", JsonPrimitive(frame))
            },
            session,
        )

        // A script the extension serves itself.
        paused("r1", "http://site.example/served.js", "Script", target)
        eventually { fake.sent("Fetch.fulfillRequest").isNotEmpty() }
        val fulfilled = fake.sent("Fetch.fulfillRequest").single().params
        assertEquals("ok()", String(Base64.getDecoder().decode(fulfilled.getValue("body").jsonPrimitive.content)))
        assertEquals(
            200,
            fulfilled
                .getValue("responseCode")
                .jsonPrimitive.content
                .toInt(),
        )
        val headers =
            (fulfilled.getValue("responseHeaders") as JsonArray).map {
                it.jsonObject.string("name") to
                    it.jsonObject.string("value")
            }
        assertTrue("Content-Type" to "application/javascript; charset=utf-8" in headers, headers.toString())

        // Anything else continues.
        paused("r2", "http://site.example/style.css", "Stylesheet", target)
        eventually { fake.sent("Fetch.continueRequest").any { it.params.string("requestId") == "r2" } }

        // A navigation the page starts goes to shouldOverrideUrlLoading, on the main looper.
        paused("r3", "http://site.example/elsewhere", "Document", target)
        assertEquals("http://site.example/elsewhere on main", overridden.poll(5, TimeUnit.SECONDS))
        eventually { fake.sent("Fetch.failRequest").any { it.params.string("requestId") == "r3" } }
    }

    @Test
    fun `script results follow Android's JSON`() {
        fun result(value: JsonObject) = CdpWebView.scriptResult(buildJsonObject { put("result", value) })
        assertEquals(
            "\"a\\\"b\"",
            result(
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("value", JsonPrimitive("a\"b"))
                },
            ),
        )
        assertEquals(
            "42",
            result(
                buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("value", JsonPrimitive(42))
                },
            ),
        )
        assertEquals(
            """{"a":[1,true]}""",
            result(
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("value", buildJsonObject { put("a", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(true)))) })
                },
            ),
        )
        assertEquals("null", result(buildJsonObject { put("type", JsonPrimitive("function")) }))
        assertEquals(
            "null",
            result(
                buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("unserializableValue", JsonPrimitive("NaN"))
                },
            ),
        )
        assertEquals("null", CdpWebView.scriptResult(buildJsonObject { put("exceptionDetails", buildJsonObject {}) }))
    }

    @Test
    fun `net errors map to Android's codes`() {
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP, CdpWebView.errorCode("net::ERR_NAME_NOT_RESOLVED"))
        assertEquals(WebViewClient.ERROR_CONNECT, CdpWebView.errorCode("net::ERR_CONNECTION_REFUSED"))
        assertEquals(WebViewClient.ERROR_TIMEOUT, CdpWebView.errorCode("net::ERR_TIMED_OUT"))
        assertEquals(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, CdpWebView.errorCode("net::ERR_CERT_AUTHORITY_INVALID"))
        assertEquals(WebViewClient.ERROR_UNKNOWN, CdpWebView.errorCode("net::ERR_FAILED"))
    }

    @Test
    fun `only clients that intercept or override need requests paused`() {
        assertFalse(CdpWebView.interceptsRequests(WebViewClient()))
        assertFalse(CdpWebView.interceptsRequests(object : WebViewClient() {}))
        assertTrue(
            CdpWebView.interceptsRequests(
                object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        url: String,
                    ) = false
                },
            ),
        )
    }

    /** An exposed object, as extensions write them: not public, one method marked. */
    private class Exposed {
        val seen = CopyOnWriteArrayList<String>()

        @JavascriptInterface
        fun deliver(
            text: String?,
            count: Int,
            flag: Boolean,
        ) {
            seen += "$text $count $flag"
        }

        @Suppress("unused")
        fun hidden(text: String) {
            seen += "hidden $text"
        }
    }

    @Test
    fun `JavaScript interfaces expose marked methods only, with converted arguments`() {
        val target = Exposed()
        val bridge = JsBridge.of(target, "android")
        bridge.call("""{"m":"deliver","a":["hello",2.0,true]}""")
        bridge.call("""{"m":"deliver","a":[null,"7",0]}""")
        bridge.call("""{"m":"hidden","a":["x"]}""")
        bridge.call("""{"m":"deliver","a":["wrong count"]}""")
        bridge.call("not json")
        assertEquals(listOf("hello 2 true", "null 7 false"), target.seen)
        val script = bridge.script()
        assertTrue("\"android\"" in script && "\"deliver\"" in script && "hidden" !in script, script)
        assertEquals("__panelarrBridge_android", bridge.binding)
    }
}
