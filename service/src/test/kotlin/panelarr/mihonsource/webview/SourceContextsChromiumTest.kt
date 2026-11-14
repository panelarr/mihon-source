// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Cookie
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import panelarr.mihonsource.compat.AndroidHost
import panelarr.mihonsource.compat.SourceCaller
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.HeadlessChromium
import panelarr.mihonsource.network.EffectiveProfile
import panelarr.mihonsource.network.FileCookieJar
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Per-source browser contexts against a real headless Chromium: cookies both ways between a
 * source's WebViews and its HTTP client, its user agent, isolation between sources, and its
 * proxy (a local test proxy).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceContextsChromiumTest {
    private lateinit var chromium: HeadlessChromium
    private lateinit var site: FakeSite
    private lateinit var browser: CdpBrowser
    private lateinit var provider: CdpWebViewProvider
    private val settings = ConcurrentHashMap<Long, WebViewSourceSettings>()
    private val views = CopyOnWriteArrayList<WebView>()

    @BeforeAll
    fun start() {
        chromium = HeadlessChromium.start()
        site = FakeSite()
        site.override = { request ->
            when (request.url.encodedPath) {
                // A page that sets one cookie by header and one by script.
                "/set" -> {
                    html("Set", "document.cookie = 'fromPage=page-value; path=/';")
                        .newBuilder()
                        .addHeader("Set-Cookie", "fromHeader=header-value; Path=/")
                        .build()
                }

                "/echo" -> {
                    html("Echo", "")
                }

                else -> {
                    null
                }
            }
        }
        val config = WebViewConfig(chromium.url, loadTimeout = Duration.ofSeconds(LOAD_SECONDS))
        browser = CdpBrowser(config)
        provider = CdpWebViewProvider(browser, config, { id -> settings[id] ?: fail("no settings for source $id") })
    }

    @AfterAll
    fun stop() {
        if (::browser.isInitialized) browser.close()
        if (::site.isInitialized) site.close()
        if (::chromium.isInitialized) chromium.close()
    }

    @AfterEach
    fun tearDown() {
        views.forEach { view -> Handler(Looper.getMainLooper()).post { view.destroy() } }
        views.clear()
        AndroidHost.webViewProvider = null
    }

    private fun source(
        id: Long,
        dir: Path,
        proxy: EffectiveProfile.Proxy? = null,
    ): FileCookieJar {
        val jar = FileCookieJar(dir.resolve("cookies-$id.json"))
        settings[id] = WebViewSourceSettings(proxy, { host -> "PanelarrTest/$id (+$host)" }, jar)
        return jar
    }

    private class Recorder : WebViewClient() {
        val events = LinkedBlockingQueue<String>()

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            events += "finished $url"
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) events += "error ${error.description}"
        }

        fun next(): String = events.poll(LOAD_SECONDS, TimeUnit.SECONDS) ?: fail("no callback within ${LOAD_SECONDS}s")
    }

    /** Loads [url] in a new view of [sourceId] (built as its call would build it) and waits until it finished. */
    private fun load(
        sourceId: Long,
        url: String,
    ): WebView {
        AndroidHost.webViewProvider = provider
        val view = SourceCaller.with(sourceId) { WebView(AndroidHost.application) }
        views += view
        view.settings.javaScriptEnabled = true
        val recorder = Recorder()
        view.webViewClient = recorder
        view.loadUrl(url)
        assertEquals("finished $url", recorder.next())
        return view
    }

    private fun evaluate(
        view: WebView,
        script: String,
    ): String {
        val answer = LinkedBlockingQueue<String>()
        view.evaluateJavascript(script, ValueCallback { answer += it })
        return answer.poll(LOAD_SECONDS, TimeUnit.SECONDS) ?: fail("no script result")
    }

    private fun cookieHeader(path: String): String? = site.requestsTo(path).last().headers["Cookie"]

    @Test
    fun `cookies move both ways between a source's WebViews and its HTTP client, which share its user agent`(
        @TempDir dir: Path,
    ) {
        val jar = source(1, dir)
        // A cookie the page set, by header and by script, is in the jar when the page has finished.
        load(1, "${site.baseUrl}/set")
        val stored = jar.all().associate { it.name to it.value }
        assertEquals("header-value", stored["fromHeader"])
        assertEquals("page-value", stored["fromPage"])
        // ... so the source's next OkHttp request sends it.
        val client = OkHttpClient.Builder().cookieJar(jar).build()
        client.newCall(Request.Builder().url("${site.baseUrl}/echo").build()).execute().use { assertTrue(it.isSuccessful) }
        assertContains(cookieHeader("/echo").orEmpty(), "fromPage=page-value")
        assertContains(cookieHeader("/echo").orEmpty(), "fromHeader=header-value")

        // A cookie the HTTP client got (here put straight in the jar) is sent by the next WebView load.
        jar.merge(listOf(Cookie.parse(site.baseUrl.toHttpUrl(), "fromJar=jar-value; Path=/")!!))
        val view = load(1, "${site.baseUrl}/echo")
        assertContains(cookieHeader("/echo").orEmpty(), "fromJar=jar-value")
        // And it's in the page.
        assertContains(evaluate(view, "document.cookie"), "fromJar=jar-value")

        // The browser speaks with the source's user agent, the one its HTTP client sends.
        val agent = "PanelarrTest/1 (+${site.baseUrl.toHttpUrl().host})"
        assertEquals(agent, site.requestsTo("/echo").last().headers["User-Agent"])
        assertEquals("\"$agent\"", evaluate(view, "navigator.userAgent"))
    }

    @Test
    fun `one source's cookies are never visible to another's WebViews`(
        @TempDir dir: Path,
    ) {
        val a = source(10, dir)
        val b = source(11, dir)
        load(10, "${site.baseUrl}/set")
        assertTrue(a.all().isNotEmpty())

        val view = load(11, "${site.baseUrl}/echo")
        assertNull(cookieHeader("/echo"), "source B's load sent no cookie")
        assertEquals("\"\"", evaluate(view, "document.cookie"))
        assertTrue(b.all().isEmpty(), "source B's jar got nothing of source A's")
    }

    @Test
    fun `a source with a proxy loads its pages through it`(
        @TempDir dir: Path,
    ) {
        TestProxy().use { proxy ->
            source(20, dir, EffectiveProfile.Proxy(java.net.Proxy.Type.HTTP, "127.0.0.1", proxy.port, null, null))
            // A name only the proxy knows: the page can load through the proxy alone.
            val view = load(20, "http://proxied.test/page")
            assertEquals("\"Proxied\"", evaluate(view, "document.title"))
            assertEquals(listOf("proxied.test"), proxy.requests.map { it.headers["Host"] }.distinct())
            // Another source without a proxy never reaches it.
            source(21, dir)
            load(21, "${site.baseUrl}/echo")
            assertTrue(proxy.requests.none { it.headers["Host"] != "proxied.test" })
        }
    }

    @Test
    fun `a proxy that wants a password gets the profile's credentials`(
        @TempDir dir: Path,
    ) {
        TestProxy(credentials = "user" to "proxy-secret").use { proxy ->
            source(30, dir, EffectiveProfile.Proxy(java.net.Proxy.Type.HTTP, "127.0.0.1", proxy.port, "user", "proxy-secret"))
            val view = load(30, "http://proxied.test/page")
            assertEquals("\"Proxied\"", evaluate(view, "document.title"))
            assertEquals(Credentials.basic("user", "proxy-secret"), proxy.requests.last().headers["Proxy-Authorization"])
        }
    }

    /** A forward HTTP proxy that serves every page itself, and may ask for Basic credentials first. */
    private class TestProxy(
        credentials: Pair<String, String>? = null,
    ) : AutoCloseable {
        private val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val port: Int get() = server.port

        init {
            val expected = credentials?.let { Credentials.basic(it.first, it.second) }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (expected != null && request.headers["Proxy-Authorization"] != expected) {
                            return MockResponse
                                .Builder()
                                .code(PROXY_AUTH_REQUIRED)
                                .addHeader("Proxy-Authenticate", "Basic realm=\"test\"")
                                .build()
                        }
                        requests += request
                        return html("Proxied", "")
                    }
                }
            server.start()
        }

        override fun close() = server.close()
    }

    private companion object {
        const val LOAD_SECONDS = 30L
        const val PROXY_AUTH_REQUIRED = 407

        fun html(
            title: String,
            script: String,
        ): MockResponse =
            MockResponse
                .Builder()
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html><head><title>$title</title></head><body><script>$script</script></body></html>")
                .build()
    }
}
