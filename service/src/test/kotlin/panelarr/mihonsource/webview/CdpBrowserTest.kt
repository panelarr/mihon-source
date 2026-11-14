// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeCdp
import panelarr.mihonsource.metrics.Metrics
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdpBrowserTest {
    private val fake = FakeCdp()
    private val metrics = Metrics()
    private val browsers = mutableListOf<CdpBrowser>()

    @AfterTest
    fun tearDown() {
        browsers.forEach { it.close() }
        fake.close()
    }

    private fun browser(
        url: String = fake.url,
        maxPages: Int = 2,
        loadTimeout: Duration = Duration.ofSeconds(5),
        idleTimeout: Duration = Duration.ofMinutes(5),
        http: OkHttpClient = CdpBrowser.defaultClient(),
    ): CdpBrowser = CdpBrowser(WebViewConfig(url, maxPages, loadTimeout, idleTimeout), http, metrics).also { browsers += it }

    /** Resolves the made-up name `webview` (a compose service name, say) to this machine. */
    private val namedHost =
        CdpBrowser
            .defaultClient()
            .newBuilder()
            .dns { host -> if (host == "webview") listOf(InetAddress.getLoopbackAddress()) else Dns.SYSTEM.lookup(host) }
            .build()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `connects where it's configured, not where the browser says it is`() =
        runBlocking {
            val status = browser().check()
            assertTrue(status.up)
            assertEquals("FakeChrome/1.0", status.browser)
            assertEquals(1, fake.connections.get())
        }

    @Test
    fun `says localhost to the browser when it's reached by name`() =
        runBlocking {
            // Chromium refuses DevTools requests whose Host header is a name.
            assertTrue(browser(url = "http://webview:${fake.port}", http = namedHost).check().up)
            assertEquals(listOf("localhost:${fake.port}", "localhost:${fake.port}"), fake.hostHeaders.toList())
        }

    @Test
    fun `keeps the Host header for an IP address`() =
        runBlocking {
            assertTrue(browser(url = "http://127.0.0.1:${fake.port}").check().up)
            assertTrue(fake.hostHeaders.all { it == "127.0.0.1:${fake.port}" }, fake.hostHeaders.toString())
        }

    @Test
    fun `a ws URL is used as it is, without discovery`() =
        runBlocking {
            assertTrue(browser(url = "ws://127.0.0.1:${fake.port}/devtools/browser/fake-id").check().up)
            assertEquals(1, fake.hostHeaders.size, "only the WebSocket upgrade, no /json/version")
        }

    @Test
    fun `an unreachable browser is reported as down, not thrown`() =
        runBlocking {
            val browser = browser(url = "http://127.0.0.1:${freePort()}")
            val status = browser.check()
            assertFalse(status.up)
            assertContains(status.error.orEmpty(), "can't reach the browser")
            assertFailsWith<WebViewUnavailableException> { browser.withPage { } }
            assertContains(metrics.scrape(), "mihon_webview_failures_total{kind=\"connect\"}")
            assertContains(metrics.scrape(), "mihon_webview_up 0.0")
        }

    @Test
    fun `a browser that refuses the WebSocket is unavailable`() =
        runBlocking {
            fake.accepting = false
            assertFailsWith<WebViewUnavailableException> { browser().connection() }
        }

    @Test
    fun `checks are reused for a few seconds`() =
        runBlocking {
            val browser = browser()
            browser.check()
            browser.check()
            assertEquals(1, fake.sent("Browser.getVersion").size)
        }

    @Test
    fun `reconnects on the next call after the browser went away`() =
        runBlocking {
            val browser = browser()
            val first = browser.connection()
            browser.withPage { it.evaluate("1") }
            // Waiting commands fail at once when the connection drops, instead of hanging until their timeout.
            fake.handler = { if (it.method == "Runtime.evaluate") FakeCdp.Ignore else null }
            val waiting = async { runCatching { first.send("Runtime.evaluate") }.exceptionOrNull() }
            delay(100)
            fake.dropConnections()
            assertTrue(waiting.await() is WebViewUnavailableException)
            assertFalse(first.isOpen)
            fake.handler = { null }

            assertEquals("evaluated: 2", (browser.withPage { it.evaluate("2") } as JsonPrimitive).content)
            assertEquals(2, fake.connections.get())
            assertContains(metrics.scrape(), "mihon_webview_failures_total{kind=\"disconnect\"}")
        }

    @Test
    fun `a command without an answer times out`() =
        runBlocking {
            fake.handler = { if (it.method == "Runtime.evaluate") FakeCdp.Ignore else null }
            val browser = browser(loadTimeout = Duration.ofMillis(300))
            assertFailsWith<CdpTimeoutException> { browser.withPage { it.evaluate("1") } }
            assertContains(metrics.scrape(), "mihon_webview_failures_total{kind=\"timeout\"}")
        }

    @Test
    fun `browser errors become protocol errors`() =
        runBlocking {
            fake.handler = { if (it.method == "Emulation.setUserAgentOverride") FakeCdp.Error("Invalid parameters") else null }
            val e = assertFailsWith<CdpProtocolException> { browser().withPage { it.send("Emulation.setUserAgentOverride") } }
            assertEquals("Invalid parameters", e.message)
            assertEquals(-32000L, e.code)
        }

    @Test
    fun `pages are opened in a session, and closed afterwards even on failure`() =
        runBlocking {
            val browser = browser()
            browser.withPage { page ->
                assertEquals(1, browser.openPages())
                assertTrue(page.targetId in fake.targets)
                page.send("Network.enable")
            }
            assertEquals(0, browser.openPages())
            assertTrue(fake.targets.isEmpty())
            val sessionCommand = fake.sent("Network.enable").single()
            assertEquals(
                "session-" +
                    fake.sent("Target.attachToTarget").single().params["targetId"].let {
                        (it as JsonPrimitive).content
                    },
                sessionCommand.sessionId,
            )
            assertEquals(JsonPrimitive(true), fake.sent("Target.attachToTarget").single().params["flatten"])
            assertTrue(fake.sent("Page.enable").isNotEmpty())

            assertFailsWith<IllegalStateException> { browser.withPage { error("boom") } }
            assertTrue(fake.targets.isEmpty())
        }

    @Test
    fun `navigate waits for the load event`() =
        runBlocking {
            val loaded = CompletableDeferred<Unit>()
            fake.after = { emptyList() }
            val browser = browser()
            browser.withPage { page ->
                val navigation = launch { page.navigate("http://site.example/") }
                delay(200)
                assertFalse(navigation.isCompleted, "returned before the load event")
                fake.emit("Page.loadEventFired", sessionId = page.sessionId)
                navigation.join()
                loaded.complete(Unit)
            }
            loaded.await()
            assertContains(metrics.scrape(), "mihon_webview_loads_total{outcome=\"ok\"} 1.0")
        }

    @Test
    fun `another page's load event doesn't count`() =
        runBlocking {
            fake.after = { emptyList() }
            val browser = browser(loadTimeout = Duration.ofMillis(500))
            browser.withPage { page ->
                val navigation = async { runCatching { page.navigate("http://site.example/") } }
                delay(100)
                fake.emit("Page.loadEventFired", sessionId = "someone-else")
                assertTrue(navigation.await().exceptionOrNull() is CdpTimeoutException)
            }
        }

    @Test
    fun `a load that takes too long is stopped and fails cleanly`() =
        runBlocking {
            fake.after = { emptyList() }
            val browser = browser(loadTimeout = Duration.ofMillis(300))
            val e = assertFailsWith<CdpTimeoutException> { browser.withPage { it.navigate("http://site.example/") } }
            assertContains(e.message.orEmpty(), "to load")
            assertEquals(1, fake.sent("Page.stopLoading").size)
            assertTrue(fake.targets.isEmpty())
            assertContains(metrics.scrape(), "mihon_webview_loads_total{outcome=\"timeout\"} 1.0")
        }

    @Test
    fun `a page the browser can't load fails with its error`() =
        runBlocking {
            fake.handler = { command ->
                if (command.method == "Page.navigate") {
                    FakeCdp.Result(buildJsonObject { put("errorText", JsonPrimitive("net::ERR_NAME_NOT_RESOLVED")) })
                } else {
                    null
                }
            }
            val e = assertFailsWith<CdpLoadException> { browser().withPage { it.navigate("http://nowhere.example/") } }
            assertContains(e.message.orEmpty(), "ERR_NAME_NOT_RESOLVED")
            assertContains(metrics.scrape(), "mihon_webview_loads_total{outcome=\"error\"} 1.0")
        }

    @Test
    fun `a same-document navigation doesn't wait for a load event`() =
        runBlocking {
            fake.after = { emptyList() }
            fake.handler =
                { if (it.method == "Page.navigate") FakeCdp.Result(buildJsonObject { put("frameId", JsonPrimitive("f")) }) else null }
            browser(loadTimeout = Duration.ofSeconds(30)).withPage { it.navigate("http://site.example/#part") }
        }

    @Test
    fun `evaluate returns the value, and a throwing script fails`() =
        runBlocking {
            val browser = browser()
            assertEquals(JsonPrimitive("evaluated: document.title"), browser.withPage { it.evaluate("document.title") })
            val evaluate = fake.sent("Runtime.evaluate").single().params
            assertEquals(JsonPrimitive(true), evaluate["returnByValue"])
            assertEquals(JsonPrimitive(true), evaluate["awaitPromise"])

            fake.handler = { command ->
                if (command.method == "Runtime.evaluate") {
                    FakeCdp.Result(
                        buildJsonObject {
                            put("result", buildJsonObject { put("type", JsonPrimitive("object")) })
                            put(
                                "exceptionDetails",
                                buildJsonObject {
                                    put("text", JsonPrimitive("Uncaught"))
                                    put(
                                        "exception",
                                        buildJsonObject {
                                            put(
                                                "description",
                                                JsonPrimitive("ReferenceError: x is not defined\n    at <anonymous>:1:1"),
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                } else {
                    null
                }
            }
            val e = assertFailsWith<CdpProtocolException> { browser.withPage { it.evaluate("x") } }
            assertEquals("the script failed: ReferenceError: x is not defined", e.message)
            fake.handler =
                { if (it.method == "Runtime.evaluate") FakeCdp.Result(buildJsonObject { put("result", buildJsonObject { }) }) else null }
            assertNull(browser.withPage { it.evaluate("undefined") })
        }

    @Test
    fun `at most max pages are open at once`() =
        runBlocking {
            val browser = browser(maxPages = 2)
            val open = AtomicInteger()
            var most = 0
            (1..6)
                .map {
                    async {
                        browser.withPage {
                            most = maxOf(most, open.incrementAndGet())
                            delay(100)
                            open.decrementAndGet()
                        }
                    }
                }.forEach { it.await() }
            assertEquals(2, most)
            assertEquals(6, fake.sent("Target.createTarget").size)
        }

    @Test
    fun `waiting for a free page gives up after the load timeout`() =
        runBlocking {
            val browser = browser(maxPages = 1, loadTimeout = Duration.ofMillis(300))
            val release = CompletableDeferred<Unit>()
            val holder = launch { browser.withPage { release.await() } }
            delay(100)
            val e = assertFailsWith<CdpTimeoutException> { browser.withPage { } }
            assertContains(e.message.orEmpty(), "busy")
            release.complete(Unit)
            holder.join()
            browser.withPage { }
            assertContains(metrics.scrape(), "mihon_webview_failures_total{kind=\"busy\"}")
        }

    @Test
    fun `pages of one key share a context, other keys get their own`() =
        runBlocking {
            val browser = browser()
            val contextOf = { fake.sent("Target.createTarget").last().params["browserContextId"] }
            browser.withPage("source-1", proxyServer = "socks5://proxy.example:1080") { }
            val first = contextOf()
            browser.withPage("source-1") { }
            assertEquals(first, contextOf())
            browser.withPage("source-2") { }
            assertNotEquals(first, contextOf())
            browser.withPage { }
            assertNull(contextOf(), "no key: the default context")

            val created = fake.sent("Target.createBrowserContext")
            assertEquals(2, created.size)
            assertEquals(JsonPrimitive("socks5://proxy.example:1080"), created[0].params["proxyServer"])
            assertEquals(JsonPrimitive(true), created[0].params["disposeOnDetach"])
            assertNull(created[1].params["proxyServer"])
            assertEquals(2, browser.openContexts())

            browser.closeContext("source-1")
            assertEquals(1, browser.openContexts())
            assertEquals(1, fake.sent("Target.disposeBrowserContext").size)
        }

    @Test
    fun `idle contexts are closed`() =
        runBlocking {
            val browser = browser(idleTimeout = Duration.ofSeconds(1))
            browser.withPage("source-1") { }
            assertEquals(1, fake.contexts.size)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (fake.contexts.isNotEmpty() && System.nanoTime() < deadline) delay(100)
            assertTrue(fake.contexts.isEmpty(), "the idle context is still open")
            assertEquals(0, browser.openContexts())
        }

    @Test
    fun `a context in use isn't closed while idle ones are`() =
        runBlocking {
            val browser = browser(idleTimeout = Duration.ofSeconds(1))
            browser.withPage("busy") {
                delay(2500)
                assertEquals(1, browser.openContexts())
            }
        }

    @Test
    fun `contexts are created again after a reconnect`() =
        runBlocking {
            val browser = browser()
            browser.withPage("source-1") { }
            fake.dropConnections()
            browser.withPage("source-1") { }
            assertEquals(2, fake.sent("Target.createBrowserContext").size)
        }

    @Test
    fun `metrics show pages open and the browser up`() =
        runBlocking {
            val browser = browser()
            browser.check()
            browser.withPage {
                assertContains(metrics.scrape(), "mihon_webview_pages_open 1.0")
            }
            assertContains(metrics.scrape(), "mihon_webview_pages_open 0.0")
            assertContains(metrics.scrape(), "mihon_webview_up 1.0")
        }
}
