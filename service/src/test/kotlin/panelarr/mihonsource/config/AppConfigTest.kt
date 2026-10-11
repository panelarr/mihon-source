// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
    }

    @Test
    fun `secrets can come from files`() {
        val file = dir.resolve("token").also { Files.writeString(it, "from-file\n") }
        assertEquals("from-file", AppConfig.fromEnv(mapOf("MIHON_ADMIN_TOKEN_FILE" to file.toString())).adminToken)
        assertEquals("direct", AppConfig.fromEnv(mapOf("MIHON_ADMIN_TOKEN" to "direct", "MIHON_ADMIN_TOKEN_FILE" to file.toString())).adminToken)
    }
}
