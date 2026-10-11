// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
    fun `secrets can come from files`() {
        val file = dir.resolve("token").also { Files.writeString(it, "from-file\n") }
        assertEquals("from-file", AppConfig.fromEnv(mapOf("MIHON_ADMIN_TOKEN_FILE" to file.toString())).adminToken)
        val both = mapOf("MIHON_ADMIN_TOKEN" to "direct", "MIHON_ADMIN_TOKEN_FILE" to file.toString())
        assertEquals("direct", AppConfig.fromEnv(both).adminToken)
    }
}
