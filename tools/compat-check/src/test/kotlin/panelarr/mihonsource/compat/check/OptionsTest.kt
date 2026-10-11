// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat.check

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptionsTest {
    private fun parse(vararg args: String) = Options.parse(args.toList(), mapOf("MIHON_ADMIN_TOKEN" to "t"))

    @Test
    fun `defaults load everything without calls`() {
        val options = parse()
        assertEquals(Options.Selection.All, options.selection)
        assertEquals(emptyList(), options.checks)
        assertEquals("t", options.token)
        assertEquals(false, options.keep)
    }

    @Test
    fun `selections and checks`() {
        assertEquals(Options.Selection.Random(5, 42), parse("--random", "5", "--seed", "42").selection)
        assertEquals(Options.Selection.Packages(listOf("a.b", "c.d")), parse("--pkg", "a.b, c.d").selection)
        assertEquals(Options.STEPS, parse("--checks", "all").checks)
        assertEquals(listOf("popular", "image"), parse("--checks", "popular,image").checks)
        assertEquals(emptyList(), parse("--checks", "none").checks)
    }

    @Test
    fun `bad input is explained`() {
        assertFailsWith<IllegalArgumentException> { parse("--checks", "everything") }
        assertFailsWith<IllegalArgumentException> { parse("--random", "0") }
        assertFailsWith<IllegalArgumentException> { parse("--bogus") }
        assertFailsWith<IllegalArgumentException> { Options.parse(emptyList(), emptyMap()) }
        assertFailsWith<HelpRequested> { parse("--help") }
    }

    @Test
    fun `load errors are classified`() {
        assertEquals("missing class", Problems.classify("NoClassDefFoundError: android/foo/Bar"))
        assertEquals("missing member", Problems.classify("NoSuchMethodError: 'void x()'"))
        assertEquals("bad bytecode", Problems.classify("VerifyError: Expecting a stackmap frame"))
        assertEquals("error", Problems.classify("RuntimeException: boom"))
    }

    @Test
    fun `the default report path is under build`() {
        assertTrue(defaultReportPath().startsWith("build/compat-reports"))
    }
}
