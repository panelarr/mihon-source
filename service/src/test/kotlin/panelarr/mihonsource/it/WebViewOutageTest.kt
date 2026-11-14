// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.rpc.ErrorInfo
import io.grpc.StatusException
import io.grpc.protobuf.StatusProto
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.CheckStep
import panelarr.mihon.v1.checkSourcesRequest
import panelarr.mihon.v1.getBrowserRequest
import panelarr.mihon.v1.testBrowserRequest
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.HeadlessChromium
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.HealthStatus
import panelarr.source.v1.getSourceRequest
import panelarr.source.v1.healthRequest
import panelarr.source.v1.listSourcesRequest
import panelarr.source.v1.popularRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The browser service going away and coming back, with a real headless Chromium: WebView
 * sources fail with `WEBVIEW_UNAVAILABLE` (health says so), other sources keep working, and
 * the next call after the browser is back works without a restart.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebViewOutageTest {
    private var chromium: HeadlessChromium? = null
    private lateinit var site: FakeSite
    private lateinit var service: TestService
    private lateinit var webViewSource: String
    private lateinit var plainSource: String

    @BeforeAll
    fun start(
        @TempDir dir: Path,
    ) {
        chromium = HeadlessChromium.start()
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"), webView = WebViewConfig(chromium!!.url, loadTimeout = Duration.ofSeconds(30)))
        service.install(TestExtensions.webview)
        service.install(TestExtensions.lib16)
        val sources = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList
        webViewSource = sources.single { it.name == "WebView Test" }.id
        plainSource = sources.first { it.name != "WebView Test" }.id
    }

    @AfterAll
    fun stop() {
        if (::service.isInitialized) service.close()
        if (::site.isInitialized) site.close()
        chromium?.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private fun popular(id: String) =
        runBlocking {
            service.sources.popular(
                popularRequest {
                    sourceId = id
                    page = 1
                },
            )
        }

    private fun errorInfo(error: StatusException): ErrorInfo =
        StatusProto
            .fromThrowable(error)!!
            .detailsList
            .single { it.`is`(ErrorInfo::class.java) }
            .unpack(ErrorInfo::class.java)

    @Test
    fun `WebView sources fail as WEBVIEW_UNAVAILABLE while the browser is down, and recover when it's back`() {
        assertEquals(2, popular(webViewSource).seriesCount)
        val browser = runBlocking { service.integrations.getBrowser(getBrowserRequest {}) }.browser
        assertTrue(browser.configured && browser.up, browser.toString())
        assertTrue(runBlocking { service.integrations.testBrowser(testBrowserRequest {}) }.ok)

        val port = chromium!!.port
        chromium!!.close()
        chromium = null

        // A WebView source fails, and says why; the contract reason stays SOURCE_DOWN.
        val error = assertFailsWith<StatusException> { popular(webViewSource) }
        val info = errorInfo(error)
        assertEquals("SOURCE_DOWN", info.reason)
        assertEquals("WEBVIEW_UNAVAILABLE", info.metadataMap["detail"])
        assertContains(error.status.description.orEmpty(), "the browser service for WebView sources is unreachable")

        // Other sources keep working.
        assertEquals(2, popular(plainSource).seriesCount)

        // Health: the service is degraded, the source's check reports the reason, the admin card shows it down.
        val health = runBlocking { service.sources.health(healthRequest {}) }
        assertEquals(HealthStatus.HEALTH_STATUS_DEGRADED, health.status)
        val check =
            runBlocking {
                service.admin.checkSources(
                    checkSourcesRequest {
                        sourceIds += webViewSource
                        steps += CheckStep.CHECK_STEP_POPULAR
                    },
                )
            }.resultsList.single()
        assertEquals("WEBVIEW_UNAVAILABLE", check.stepsList.single().reason)
        val sourceHealth = runBlocking { service.sources.getSource(getSourceRequest { sourceId = webViewSource }) }.source.health
        assertEquals("WEBVIEW_UNAVAILABLE", sourceHealth.reason)
        val down = runBlocking { service.integrations.getBrowser(getBrowserRequest {}) }.browser
        assertTrue(down.configured && !down.up, down.toString())
        val test = runBlocking { service.integrations.testBrowser(testBrowserRequest {}) }
        assertTrue(!test.ok && test.message.isNotEmpty(), test.toString())

        // The browser comes back on the same address: the next call works, no restart needed.
        chromium = HeadlessChromium.start(port)
        assertEquals(2, popular(webViewSource).seriesCount)
        assertEquals(2, popular(plainSource).seriesCount)
    }
}
