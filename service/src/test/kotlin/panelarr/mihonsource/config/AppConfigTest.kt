// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppConfigTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `defaults`() {
        val config = AppConfig.fromEnv(emptyMap())
        assertEquals(8902, config.port)
        assertNull(config.adminToken)
        assertNull(config.metricsPort)
        assertEquals(emptySet(), config.healthCheckLangs)
        assertEquals(10, config.healthCheckSample)
    }

    @Test
    fun `health check languages and sample`() {
        val config = AppConfig.fromEnv(mapOf("MIHON_HEALTH_CHECK_LANGS" to " en, fr ,", "MIHON_HEALTH_CHECK_SAMPLE" to "25"))
        assertEquals(setOf("en", "fr"), config.healthCheckLangs)
        assertEquals(25, config.healthCheckSample)
        assertNull(AppConfig.fromEnv(mapOf("MIHON_HEALTH_CHECK_SAMPLE" to "all")).healthCheckSample)
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf("MIHON_HEALTH_CHECK_SAMPLE" to "0")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf("MIHON_HEALTH_CHECK_SAMPLE" to "some")) }
    }

    @Test
    fun `the browser service is off without a URL`() {
        assertNull(AppConfig.fromEnv(emptyMap()).webView)
        assertNull(AppConfig.fromEnv(mapOf("MIHON_WEBVIEW_URL" to " ", "MIHON_WEBVIEW_MAX_PAGES" to "4")).webView)
    }

    @Test
    fun `browser service settings`() {
        val defaults = AppConfig.fromEnv(mapOf("MIHON_WEBVIEW_URL" to "http://webview:9222/")).webView!!
        assertEquals(WebViewConfig("http://webview:9222"), defaults)
        assertEquals(2, defaults.maxPages)
        assertEquals(Duration.ofSeconds(60), defaults.loadTimeout)
        assertEquals(Duration.ofMinutes(5), defaults.idleTimeout)

        val set =
            AppConfig
                .fromEnv(
                    mapOf(
                        "MIHON_WEBVIEW_URL" to "ws://127.0.0.1:9222/devtools/browser/abc",
                        "MIHON_WEBVIEW_MAX_PAGES" to "4",
                        "MIHON_WEBVIEW_LOAD_TIMEOUT" to "90s",
                        "MIHON_WEBVIEW_IDLE_TIMEOUT" to "1h",
                    ),
                ).webView!!
        assertEquals(WebViewConfig("ws://127.0.0.1:9222/devtools/browser/abc", 4, Duration.ofSeconds(90), Duration.ofHours(1)), set)
    }

    @Test
    fun `bad browser service settings stop the start`() {
        val url = "MIHON_WEBVIEW_URL" to "http://webview:9222"
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf("MIHON_WEBVIEW_URL" to "webview:9222")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf("MIHON_WEBVIEW_URL" to "ftp://webview")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf(url, "MIHON_WEBVIEW_MAX_PAGES" to "0")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf(url, "MIHON_WEBVIEW_LOAD_TIMEOUT" to "0")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(mapOf(url, "MIHON_WEBVIEW_IDLE_TIMEOUT" to "soon")) }
    }

    @Test
    fun `secrets can come from files`() {
        val file = dir.resolve("token").also { Files.writeString(it, "from-file\n") }
        assertEquals("from-file", AppConfig.fromEnv(mapOf("MIHON_ADMIN_TOKEN_FILE" to file.toString())).adminToken)
        val both = mapOf("MIHON_ADMIN_TOKEN" to "direct", "MIHON_ADMIN_TOKEN_FILE" to file.toString())
        assertEquals("direct", AppConfig.fromEnv(both).adminToken)
    }
}
