// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JsonSharedPreferencesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `every type round-trips and survives a reload`() {
        val file = dir.resolve("p.json")
        JsonSharedPreferences(file)
            .edit()
            .putString("s", "text")
            .putInt("i", -7)
            .putLong("l", Long.MAX_VALUE)
            .putFloat("f", 1.5f)
            .putBoolean("b", true)
            .putStringSet("set", setOf("x", "y"))
            .commit()

        for (prefs in listOf(JsonSharedPreferences(file), JsonSharedPreferences(file))) {
            assertEquals("text", prefs.getString("s", null))
            assertEquals(-7, prefs.getInt("i", 0))
            assertEquals(Long.MAX_VALUE, prefs.getLong("l", 0))
            assertEquals(1.5f, prefs.getFloat("f", 0f))
            assertEquals(true, prefs.getBoolean("b", false))
            assertEquals(setOf("x", "y"), prefs.getStringSet("set", null))
            assertEquals(6, prefs.all.size)
        }
    }

    @Test
    fun `remove, clear and defaults`() {
        val prefs = JsonSharedPreferences(dir.resolve("p.json"))
        prefs
            .edit()
            .putString("a", "1")
            .putString("b", "2")
            .apply()
        prefs.edit().remove("a").apply()
        assertNull(prefs.getString("a", null))
        assertEquals("d", prefs.getString("missing", "d"))

        prefs
            .edit()
            .clear()
            .putString("c", "3")
            .commit()
        assertFalse(prefs.contains("b"))
        assertEquals("3", JsonSharedPreferences(dir.resolve("p.json")).getString("c", null))
    }

    @Test
    fun `a type mismatch throws like Android`() {
        val prefs = JsonSharedPreferences(dir.resolve("p.json"))
        prefs.edit().putString("a", "1").apply()
        assertFailsWith<ClassCastException> { prefs.getInt("a", 0) }
    }

    @Test
    fun `listeners hear about changed keys`() {
        val prefs = JsonSharedPreferences(dir.resolve("p.json"))
        val changed = mutableListOf<String?>()
        prefs.registerOnSharedPreferenceChangeListener { _, key -> changed += key }
        prefs.edit().putInt("n", 1).apply()
        prefs.edit().putInt("n", 1).apply()
        assertEquals(listOf<String?>("n"), changed)
    }
}
