// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import androidx.preference.CheckBoxPreference
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreferenceTest {
    private val context = AndroidHost.application

    @Test
    fun `callChangeListener asks the listener and defaults to true`() {
        val pref = EditTextPreference(context)
        assertTrue(pref.callChangeListener("anything"))

        val seen = mutableListOf<Any?>()
        pref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { p, value ->
                assertSame(pref, p)
                seen += value
                value == "ok"
            }
        assertTrue(pref.callChangeListener("ok"))
        assertFalse(pref.callChangeListener("no"))
        assertEquals(listOf<Any?>("ok", "no"), seen)
    }

    @Test
    fun `setters store and getters return`() {
        val list =
            ListPreference(context).apply {
                key = "k"
                title = "Title"
                summary = "%s"
                entries = arrayOf("One", "Two")
                entryValues = arrayOf("1", "2")
                setDefaultValue("2")
                isEnabled = false
                isVisible = false
            }
        assertEquals("k", list.key)
        assertEquals("Title", list.title)
        assertEquals("2", list.defaultValue)
        assertFalse(list.isEnabled)
        assertFalse(list.isVisible)
        assertEquals(1, list.findIndexOfValue("2"))
        list.setValueIndex(0)
        assertEquals("1", list.value)
        assertEquals("One", list.entry)
        assertSame(context, list.context)
        assertIs<DialogPreference>(list)

        val multi = MultiSelectListPreference(context).apply { values = setOf("a") }
        assertEquals(setOf("a"), multi.values)

        val switch =
            SwitchPreferenceCompat(context).apply {
                isChecked = true
                summaryOn = "on"
            }
        assertTrue(switch.isChecked)
        assertEquals("on", switch.summaryOn)
        assertIs<TwoStatePreference>(CheckBoxPreference(context))

        val edit = EditTextPreference(context).apply { dialogTitle = "dt" }
        assertEquals("dt", edit.dialogTitle)
        assertNull(edit.onBindEditTextListener)
    }

    @Test
    fun `screens keep preferences in insertion order`() {
        val screen = PreferenceScreen(context)
        val first = SwitchPreferenceCompat(screen.context).apply { key = "a" }
        val second = EditTextPreference(screen.context).apply { key = "b" }
        assertTrue(screen.addPreference(first))
        screen.addPreference(second)
        assertEquals(listOf<Preference>(first, second), screen.preferences)
        assertSame(second, screen.findPreference<Preference>("b"))
        assertSame(context, PreferenceScreen().context)
    }
}
