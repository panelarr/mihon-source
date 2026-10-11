// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.preference.PreferenceManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidHostTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        AndroidHost.configure(dir)
    }

    @Test
    fun `source preferences live in prefs by id`() {
        val prefs = AndroidHost.application.getSharedPreferences("source_1234567890123", Context.MODE_PRIVATE)
        prefs.edit().putString("a", "b").commit()

        assertTrue(Files.exists(dir.resolve("prefs/1234567890123.json")))
        assertSame(prefs, AndroidHost.sharedPreferences("source_1234567890123"))
    }

    @Test
    fun `other names are sanitised under prefs named`() {
        AndroidHost
            .sharedPreferences("plain-name_1.x")
            .edit()
            .putInt("n", 1)
            .apply()
        AndroidHost
            .sharedPreferences("../evil/name")
            .edit()
            .putInt("n", 1)
            .apply()

        assertTrue(Files.exists(dir.resolve("prefs/named/plain-name_1.x.json")))
        val named = Files.list(dir.resolve("prefs/named")).use { files -> files.map { it.fileName.toString() }.toList() }
        assertEquals(2, named.size)
        assertTrue(named.all { !it.startsWith(".") && !it.contains('/') })
    }

    @Test
    fun `deleting source preferences removes the file and the cached instance`() {
        val prefs = AndroidHost.sharedPreferences("source_42")
        prefs.edit().putBoolean("x", true).commit()

        AndroidHost.deleteSourcePreferences(42)

        assertFalse(Files.exists(dir.resolve("prefs/42.json")))
        val fresh = AndroidHost.sharedPreferences("source_42")
        assertNotSame(prefs, fresh)
        assertFalse(fresh.contains("x"))
    }

    @Test
    fun `application behaves like an Android application context`() {
        val app = AndroidHost.application
        assertIs<ContextWrapper>(app)
        assertIs<Application>(app)
        assertSame(app, app.applicationContext)
        assertEquals("app.mihonsource", app.packageName)
        assertEquals(dir.resolve("cache").toFile(), app.cacheDir)
        assertTrue(app.cacheDir.isDirectory)
        assertEquals(app.cacheDir, app.externalCacheDir)
        assertEquals(dir.resolve("files").toFile(), app.filesDir)
        assertEquals(dir.toString(), app.applicationInfo.dataDir)
    }

    @Test
    fun `default shared preferences use the default name`() {
        assertSame(AndroidHost.sharedPreferences("default"), PreferenceManager.getDefaultSharedPreferences(AndroidHost.application))
    }
}
