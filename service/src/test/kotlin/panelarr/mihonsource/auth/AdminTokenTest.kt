package panelarr.mihonsource.auth

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminTokenTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a token is generated once and only its hash is stored`() {
        val first = AdminToken.load(dir, fromEnv = null)
        val token = assertNotNull(first.generated)
        assertTrue(first.matches(token))
        assertFalse(token in Files.readString(dir.resolve("admin-token.json")))

        val second = AdminToken.load(dir, fromEnv = null)
        assertNull(second.generated)
        assertTrue(second.matches(token))
    }

    @Test
    fun `a token from the environment wins and isn't stored`() {
        val admin = AdminToken.load(dir, fromEnv = "from-env")
        assertNull(admin.generated)
        assertTrue(admin.matches("from-env"))
        assertFalse(admin.matches("other"))
        assertFalse(Files.exists(dir.resolve("admin-token.json")))
    }
}
