// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.storage

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JsonFileTest {
    @TempDir
    lateinit var dir: Path

    @Serializable
    data class Doc(
        val name: String,
    )

    private val at = Instant.parse("2026-10-28T20:41:05.806Z")

    private fun file(private: Boolean = false) = JsonFile(dir.resolve("doc.json"), Doc.serializer(), private) { at }

    @Test
    fun `a written value reads back, and no temporary file is left`() {
        file().write(Doc("one"))
        file().write(Doc("two"))

        assertEquals(Doc("two"), file().read())
        assertEquals(listOf("doc.json"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a missing file reads as null`() {
        assertNull(file().read())
    }

    @Test
    fun `a file of zeros is set aside and reads as null`() {
        val path = dir.resolve("doc.json")
        Files.write(path, ByteArray(527))

        assertNull(file().read())
        assertFalse(Files.exists(path))
        val aside = dir.resolve("doc.json.corrupt-2026-10-28T204105Z")
        assertEquals(527, Files.size(aside))

        file().write(Doc("fresh"))
        assertEquals(Doc("fresh"), file().read())
    }

    @Test
    fun `a file that isn't text is set aside too`() {
        Files.write(dir.resolve("doc.json"), byteArrayOf(0xC3.toByte(), 0x28))

        assertNull(file().read())
        assertEquals(listOf("doc.json.corrupt-2026-10-28T204105Z"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a private file is readable only by its owner`() {
        file(private = true).write(Doc("secret"))

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("doc.json"))))
    }
}
