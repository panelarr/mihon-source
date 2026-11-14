// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.HeadlessChromium
import java.net.InetAddress
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CDP client against a real headless Chromium in a container. It needs Docker: it's
 * skipped without one locally, and fails without one in CI (`CI` set), so it always runs
 * there.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChromiumIntegrationTest {
    private lateinit var chromium: GenericContainer<*>
    private lateinit var browser: CdpBrowser

    @BeforeAll
    fun start() {
        val docker = DockerClientFactory.instance().isDockerAvailable
        if (System.getenv("CI").isNullOrEmpty()) assumeTrue(docker, "Docker isn't available")
        check(docker) { "Docker isn't available, and CI needs it for this test" }
        chromium =
            GenericContainer(DockerImageName.parse(IMAGE))
                .withExposedPorts(DEVTOOLS_PORT)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)))
        chromium.start()
        // Reach it by a name, as a compose service would be, so the Host header workaround is exercised.
        val http =
            CdpBrowser
                .defaultClient()
                .newBuilder()
                .dns { host -> if (host == NAME) InetAddress.getAllByName(chromium.host).toList() else Dns.SYSTEM.lookup(host) }
                .build()
        browser =
            CdpBrowser(
                WebViewConfig("http://$NAME:${chromium.getMappedPort(DEVTOOLS_PORT)}", loadTimeout = Duration.ofSeconds(30)),
                http,
            )
    }

    @AfterAll
    fun stop() {
        if (::browser.isInitialized) browser.close()
        if (::chromium.isInitialized) chromium.stop()
    }

    @Test
    fun `connects and reports the browser`() =
        runBlocking {
            val status = browser.check()
            assertTrue(status.up, status.error)
            assertContains(status.browser.orEmpty(), "Chrome/")
        }

    @Test
    fun `loads a page and runs a script in it`() =
        runBlocking {
            val title =
                browser.withPage { page ->
                    page.navigate("data:text/html,<title>Made-up page</title><p id=n>42</p>")
                    page.evaluate("document.title + ' ' + document.getElementById('n').textContent")
                }
            assertEquals(JsonPrimitive("Made-up page 42"), title)
            val awaited = browser.withPage { it.evaluate("new Promise(r => setTimeout(() => r([1, 2, 3]), 50))") }
            assertEquals(listOf(1, 2, 3), awaited!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        }

    @Test
    fun `a throwing script and an unloadable page fail cleanly`() =
        runBlocking<Unit> {
            browser.withPage { page ->
                runCatching { page.evaluate("notDefined.x") }.exceptionOrNull().let { assertTrue(it is CdpProtocolException, "$it") }
                runCatching { page.navigate("http://127.0.0.1:1/") }.exceptionOrNull().let { assertTrue(it is CdpLoadException, "$it") }
            }
        }

    @Test
    fun `contexts keep their cookies apart`() =
        runBlocking {
            val site = "http://site.example/"
            browser.withPage("source-a") { page ->
                page.send(
                    "Network.setCookie",
                    buildJsonObject {
                        put("name", JsonPrimitive("session"))
                        put("value", JsonPrimitive("a"))
                        put("url", JsonPrimitive(site))
                    },
                )
            }
            val cookies = { key: String ->
                runBlocking {
                    browser.withPage(key) { page ->
                        page
                            .send("Network.getCookies", buildJsonObject { put("urls", JsonArray(listOf(JsonPrimitive(site)))) })
                            .getValue("cookies")
                            .jsonArray
                            .map {
                                it.jsonObject
                                    .getValue("name")
                                    .jsonPrimitive.content
                            }
                    }
                }
            }
            assertEquals(listOf("session"), cookies("source-a"), "the context keeps its cookie between pages")
            assertEquals(emptyList(), cookies("source-b"))
        }

    private companion object {
        const val IMAGE = HeadlessChromium.IMAGE
        const val DEVTOOLS_PORT = 9222
        const val NAME = "webview"
    }
}
