// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.fixtures.FakeCdp
import panelarr.source.v1.HealthStatus
import panelarr.source.v1.healthRequest
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browser service is an optional check: off by default, and degraded (never down) when unreachable. */
class WebViewHealthTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `without a browser URL nothing changes`() {
        TestService(dir.resolve("data")).use { service ->
            assertNull(service.app.webView)
            val health = runBlocking { service.sources.health(healthRequest {}) }
            assertEquals(HealthStatus.HEALTH_STATUS_SERVING, health.status)
            assertEquals("", health.message)
            assertFalse(String(service.get("/metrics").body()).contains("mihon_webview"))
        }
    }

    @Test
    fun `an unreachable browser makes the service degraded, not down`() {
        val port = ServerSocket(0).use { it.localPort }
        TestService(dir.resolve("data"), webView = WebViewConfig("http://127.0.0.1:$port")).use { service ->
            val health = runBlocking { service.sources.health(healthRequest {}) }
            assertEquals(HealthStatus.HEALTH_STATUS_DEGRADED, health.status)
            assertEquals("the browser service for WebView sources is unreachable", health.message)
            assertEquals(200, service.get("/readyz").statusCode())
            assertTrue(String(service.get("/metrics").body()).contains("mihon_webview_up 0.0"))
        }
    }

    @Test
    fun `a reachable browser keeps the service serving`() {
        FakeCdp().use { fake ->
            TestService(dir.resolve("data"), webView = WebViewConfig(fake.url)).use { service ->
                val health = runBlocking { service.sources.health(healthRequest {}) }
                assertEquals(HealthStatus.HEALTH_STATUS_SERVING, health.status)
                assertTrue(String(service.get("/metrics").body()).contains("mihon_webview_up 1.0"))
            }
        }
    }
}
