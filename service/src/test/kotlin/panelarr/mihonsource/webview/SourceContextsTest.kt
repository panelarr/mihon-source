// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihonsource.compat.AndroidHost
import panelarr.mihonsource.compat.SourceCaller
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeCdp
import panelarr.mihonsource.network.EffectiveProfile
import panelarr.mihonsource.network.FileCookieJar
import java.net.ServerSocket
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Per-source browser contexts against a fake browser: contexts, proxies, user agents, cookies, limits. */
class SourceContextsTest {
    @TempDir
    lateinit var dir: Path

    private val fakes = mutableListOf<FakeCdp>()
    private val browsers = mutableListOf<CdpBrowser>()
    private val settings = ConcurrentHashMap<Long, WebViewSourceSettings>()
    private val views = mutableListOf<WebView>()

    @AfterEach
    fun tearDown() {
        views.forEach { view -> main { view.destroy() } }
        AndroidHost.webViewProvider = null
        browsers.forEach { it.close() }
        fakes.forEach { it.close() }
    }

    /** A fake browser whose loads finish at once, except for URLs [held] keeps loading until told. */
    private fun fake(held: (String) -> Boolean = { false }): FakeCdp =
        FakeCdp().also { fake ->
            fakes += fake
            fake.after = { command ->
                val url = command.params["url"]?.jsonPrimitive?.content
                if (command.method == "Page.navigate" && url != null && !held(url)) {
                    listOf(
                        "Page.frameNavigated" to frame(url),
                        "Page.loadEventFired" to buildJsonObject { put("timestamp", JsonPrimitive(1)) },
                    )
                } else {
                    emptyList()
                }
            }
        }

    private fun frame(url: String) =
        buildJsonObject {
            put(
                "frame",
                buildJsonObject {
                    put("id", JsonPrimitive("frame"))
                    put("url", JsonPrimitive(url))
                },
            )
        }

    private fun provider(
        url: String,
        maxPages: Int = 4,
        loadTimeout: Duration = Duration.ofSeconds(5),
        idleTimeout: Duration = Duration.ofMinutes(5),
    ): CdpWebViewProvider {
        val config = WebViewConfig(url, maxPages = maxPages, loadTimeout = loadTimeout, idleTimeout = idleTimeout)
        val browser = CdpBrowser(config).also { browsers += it }
        return CdpWebViewProvider(browser, config, { id -> settings[id] ?: fail("no settings for source $id") }).also {
            AndroidHost.webViewProvider = it
        }
    }

    private fun source(
        id: Long,
        proxy: EffectiveProfile.Proxy? = null,
        userAgent: (String) -> String = { "Source-$id-UA" },
    ): FileCookieJar {
        val jar = FileCookieJar(dir.resolve("cookies-$id.json"))
        settings[id] = WebViewSourceSettings(proxy, userAgent, jar)
        return jar
    }

    /** A WebView made while working for [sourceId], as an extension's call would make it. */
    private fun view(sourceId: Long?): Pair<WebView, Recorder> {
        val view = SourceCaller.with(sourceId) { WebView(AndroidHost.application) }
        val recorder = Recorder()
        view.webViewClient = recorder
        views += view
        return view to recorder
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
            events += "error ${error.errorCode} ${error.description}"
        }

        fun next(seconds: Long = 5): String = events.poll(seconds, TimeUnit.SECONDS) ?: fail("no callback within ${seconds}s")
    }

    private fun main(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private fun eventually(
        seconds: Long = 5,
        check: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!check()) {
            if (System.nanoTime() > deadline) fail("condition not met within ${seconds}s")
            Thread.sleep(20)
        }
    }

    private fun FakeCdp.contextsOfTargets(): List<String?> =
        sent("Target.createTarget").map {
            it.params["browserContextId"]?.jsonPrimitive?.content
        }

    @Test
    fun `views of one source share its context, other sources and unknown callers get their own`() {
        val fake = fake()
        provider(fake.url)
        source(1)
        source(2)
        val (a1, r1) = view(1)
        val (a2, r2) = view(1)
        val (b, rb) = view(2)
        val (none, rn) = view(null)
        listOf(a1 to r1, a2 to r2, b to rb, none to rn).forEach { (view, recorder) ->
            view.loadUrl("http://site.example/")
            assertEquals("finished http://site.example/", recorder.next())
        }
        val used = fake.contextsOfTargets()
        assertEquals(4, used.size)
        assertEquals(used[0], used[1], "both views of source 1 use one context")
        assertEquals(3, used.toSet().size, "source 2 and the unknown caller have contexts of their own")
        assertEquals(3, fake.sent("Target.createBrowserContext").size)

        // A view for no known source gives its context back with its page; a source's context stays for its next views.
        main { none.destroy() }
        eventually { fake.sent("Target.disposeBrowserContext").size == 1 }
        main { a1.destroy() }
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(1, fake.sent("Target.disposeBrowserContext").size)
    }

    @Test
    fun `a source's context uses its proxy, and a changed proxy gets a new context`() {
        val fake = fake()
        provider(fake.url)
        val proxy = EffectiveProfile.Proxy(java.net.Proxy.Type.HTTP, "proxy.example", 8080, null, null)
        source(1, proxy = proxy)
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/")
        assertEquals("finished http://site.example/", recorder.next())
        val created = fake.sent("Target.createBrowserContext")
        assertEquals(
            "http://proxy.example:8080",
            created
                .single()
                .params["proxyServer"]
                ?.jsonPrimitive
                ?.content,
        )

        // The profile switches proxies: the next page opens in a new context, created with the new one.
        settings[1] = settings.getValue(1).copy(proxy = proxy.copy(type = java.net.Proxy.Type.SOCKS, port = 1080))
        main { view.destroy() }
        val (next, nextRecorder) = view(1)
        next.loadUrl("http://site.example/")
        assertEquals("finished http://site.example/", nextRecorder.next())
        assertEquals(
            listOf("http://proxy.example:8080", "socks5://proxy.example:1080"),
            fake.sent("Target.createBrowserContext").map { it.params["proxyServer"]?.jsonPrimitive?.content },
        )
        // Context keys never carry the proxy's address.
        assertTrue(browsers.single().contextInfo.none { "proxy.example" in it.key })
    }

    @Test
    fun `a SOCKS proxy with a password fails the load with a reason, as the browser can't use one`() {
        val fake = fake()
        provider(fake.url)
        source(1, proxy = EffectiveProfile.Proxy(java.net.Proxy.Type.SOCKS, "proxy.example", 1080, "user", "secret"))
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/")
        val error = recorder.next()
        assertContains(error, "SOCKS proxy that needs a username and password")
        assertEquals(0, fake.sent("Target.createTarget").size)
    }

    @Test
    fun `proxy credentials answer the proxy's challenge once, and nothing else's`() {
        val fake = fake()
        provider(fake.url)
        source(1, proxy = EffectiveProfile.Proxy(java.net.Proxy.Type.HTTP, "proxy.example", 8080, "user", "secret"))
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/")
        assertEquals("finished http://site.example/", recorder.next())
        val enable = fake.sent("Fetch.enable").single()
        assertEquals("true", enable.params["handleAuthRequests"]?.jsonPrimitive?.content)
        val session = enable.sessionId

        fun challenge(
            id: String,
            source: String,
        ) = fake.emit(
            "Fetch.authRequired",
            buildJsonObject {
                put("requestId", JsonPrimitive(id))
                put("authChallenge", buildJsonObject { put("source", JsonPrimitive(source)) })
            },
            session,
        )

        challenge("r1", "Proxy")
        eventually { fake.sent("Fetch.continueWithAuth").size == 1 }
        // Asked again for the same request: the credentials were refused, so give up rather than loop.
        challenge("r1", "Proxy")
        challenge("r2", "Server")
        eventually { fake.sent("Fetch.continueWithAuth").size == 3 }
        val answers =
            fake.sent("Fetch.continueWithAuth").map {
                val response = it.params.getValue("authChallengeResponse").jsonObject
                "${it.params.getValue("requestId").jsonPrimitive.content} ${response.getValue("response").jsonPrimitive.content} " +
                    (response["username"]?.jsonPrimitive?.content ?: "-")
            }
        assertEquals(listOf("r1 ProvideCredentials user", "r1 CancelAuth -", "r2 CancelAuth -"), answers)
    }

    @Test
    fun `a view uses the source's user agent unless the extension sets its own`() {
        val fake = fake()
        provider(fake.url)
        source(1, userAgent = { host -> "Source-UA for $host" })
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/")
        assertEquals("finished http://site.example/", recorder.next())
        view.settings.userAgentString = "Extension-UA"
        view.loadUrl("http://site.example/again")
        assertEquals("finished http://site.example/again", recorder.next())
        assertEquals(
            listOf("Source-UA for site.example", "Extension-UA"),
            fake.sent("Emulation.setUserAgentOverride").map {
                it.params
                    .getValue("userAgent")
                    .jsonPrimitive.content
            },
        )
    }

    @Test
    fun `cookies go from the source's jar into its context before a load, and back after`() {
        val fake = fake()
        fake.handler = { command ->
            if (command.method == "Storage.getCookies") {
                FakeCdp.Result(
                    buildJsonObject {
                        put(
                            "cookies",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("name", JsonPrimitive("clearance"))
                                        put("value", JsonPrimitive("from-page"))
                                        put("domain", JsonPrimitive(".site.example"))
                                        put("path", JsonPrimitive("/"))
                                        put("session", JsonPrimitive(true))
                                    },
                                ),
                            ),
                        )
                    },
                )
            } else {
                null
            }
        }
        provider(fake.url)
        val jar = source(1)
        jar.merge(listOf(Cookie.parse("http://site.example/".toHttpUrl(), "login=from-jar; Path=/")!!))
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/page")
        assertEquals("finished http://site.example/page", recorder.next())

        val set = fake.sent("Storage.setCookies").first()
        val context = fake.contextsOfTargets().single()
        assertEquals(context, set.params["browserContextId"]?.jsonPrimitive?.content)
        val pushed = (set.params.getValue("cookies") as JsonArray).single().jsonObject
        assertEquals("login", pushed.getValue("name").jsonPrimitive.content)
        assertEquals("http://site.example/", pushed.getValue("url").jsonPrimitive.content)
        assertNull(pushed["domain"], "a host-only cookie stays host-only")
        assertTrue(fake.sent("Storage.getCookies").all { it.params["browserContextId"]?.jsonPrimitive?.content == context })
        // The page's cookie reached the jar, so the source's HTTP client sends it.
        val sent = jar.loadForRequest("http://www.site.example/".toHttpUrl()).associate { it.name to it.value }
        assertEquals(mapOf("clearance" to "from-page"), sent)
    }

    @Test
    fun `loads of one host in one source take turns, other hosts and sources don't wait`() {
        val fake = fake(held = { it.endsWith("/held") })
        provider(fake.url)
        source(1)
        source(2)
        val (first, firstRecorder) = view(1)
        first.loadUrl("http://site.example/held")
        eventually { fake.sent("Page.navigate").size == 1 }

        val (second, secondRecorder) = view(1)
        second.loadUrl("http://site.example/second")
        val (other, otherRecorder) = view(1)
        other.loadUrl("http://other.example/")
        val (elsewhere, elsewhereRecorder) = view(2)
        elsewhere.loadUrl("http://site.example/elsewhere")
        assertEquals("finished http://other.example/", otherRecorder.next())
        assertEquals("finished http://site.example/elsewhere", elsewhereRecorder.next())
        Thread.sleep(SETTLE_MILLIS)
        assertNull(secondRecorder.events.poll(), "the second load of the host waits for the first")
        assertTrue(
            fake.sent("Page.navigate").none {
                it.params
                    .getValue("url")
                    .jsonPrimitive.content
                    .endsWith("/second")
            },
        )

        val held = fake.sent("Page.navigate").first()
        fake.emit("Page.frameNavigated", frame("http://site.example/held"), held.sessionId)
        fake.emit("Page.loadEventFired", buildJsonObject { put("timestamp", JsonPrimitive(1)) }, held.sessionId)
        assertEquals("finished http://site.example/held", firstRecorder.next())
        assertEquals("finished http://site.example/second", secondRecorder.next())
    }

    @Test
    fun `pages are capped across sources, and a page given back lets the next source in`() {
        val fake = fake()
        provider(fake.url, maxPages = 1)
        source(1)
        source(2)
        val (a, ra) = view(1)
        a.loadUrl("http://site.example/a")
        assertEquals("finished http://site.example/a", ra.next())
        val (b, rb) = view(2)
        b.loadUrl("http://site.example/b")
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(1, fake.sent("Target.createTarget").size, "source 2 waits for the only page")
        assertNull(rb.events.poll())
        main { a.destroy() }
        assertEquals("finished http://site.example/b", rb.next())
        assertEquals(1, browsers.single().openPages())
    }

    @Test
    fun `a load past the timeout fails cleanly, and the view goes on`() {
        val fake = fake(held = { it.endsWith("/slow") })
        val provider = provider(fake.url, loadTimeout = Duration.ofSeconds(1))
        source(1)
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/slow")
        val error = recorder.next()
        assertTrue(error.startsWith("error ${WebViewClient.ERROR_TIMEOUT} "), error)
        assertEquals("finished http://site.example/slow", recorder.next())
        assertTrue(fake.sent("Page.stopLoading").isNotEmpty())
        assertNull(provider.outages.since(1, 0), "a slow page isn't an unreachable browser")
        view.loadUrl("http://site.example/fast")
        assertEquals("finished http://site.example/fast", recorder.next())
        main { view.destroy() }
        eventually { browsers.single().openPages() == 0 }
    }

    @Test
    fun `an idle source context closes, and the next view opens a new one`() {
        val fake = fake()
        provider(fake.url, idleTimeout = Duration.ofSeconds(2))
        source(1)
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/")
        assertEquals("finished http://site.example/", recorder.next())
        val browser = browsers.single()
        assertEquals(1, browser.openContexts())
        main { view.destroy() }
        eventually(seconds = 10) { fake.sent("Target.disposeBrowserContext").size == 1 }
        assertEquals(0, browser.openContexts())

        val (next, nextRecorder) = view(1)
        next.loadUrl("http://site.example/")
        assertEquals("finished http://site.example/", nextRecorder.next())
        val contexts = fake.contextsOfTargets()
        assertNotEquals(contexts[0], contexts[1])
    }

    @Test
    fun `an unreachable browser is noted for the source whose view found it`() {
        val port = ServerSocket(0).use { it.localPort }
        val provider = provider("http://127.0.0.1:$port")
        source(1)
        source(2)
        val before = System.nanoTime()
        val (view, recorder) = view(1)
        view.loadUrl("http://site.example/")
        val error = recorder.next()
        assertContains(error, "the browser service is unavailable")
        assertNotNull(provider.outages.since(1, before))
        assertNull(provider.outages.since(2, before))
        assertNull(provider.outages.since(1, System.nanoTime()), "only failures since a call started count for it")
    }

    private companion object {
        /** Long enough for a load that wrongly went ahead to show up. */
        const val SETTLE_MILLIS = 500L
    }
}
