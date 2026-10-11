// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IcuSimpleDateFormatTest {
    @Test
    fun `ISO offsets with ZZZZZ parse like on Android`() {
        val pattern = "yyyy-MM-dd'T'HH:mm:ssZZZZZ"
        val icu = IcuSimpleDateFormat(pattern, Locale.US)
        assertEquals(1_700_000_000_000, icu.parse("2023-11-14T22:13:20+00:00")!!.time)
        assertEquals(1_700_000_000_000, icu.parse("2023-11-15T00:13:20+02:00")!!.time)
        // The JDK reads ZZZZZ as +0000 and rejects the same input.
        assertNull(SimpleDateFormat(pattern, Locale.US).parse("2023-11-14T22:13:20+00:00", java.text.ParsePosition(0)))
    }

    @Test
    fun `fractional seconds are a fraction, not milliseconds`() {
        val pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'"
        val icu = IcuSimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals(1_700_000_000_123, icu.parse("2023-11-14T22:13:20.123456Z")!!.time)
        val jdk = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals(1_700_000_123_456, jdk.parse("2023-11-14T22:13:20.123456Z")!!.time)
    }

    @Test
    fun `ICU pattern letters are accepted`() {
        assertFailsWith<IllegalArgumentException> { SimpleDateFormat("cccc", Locale.US) }
        val icu = IcuSimpleDateFormat("cccc, d MMM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("Tuesday, 14 Nov", icu.format(java.util.Date(1_700_000_000_000)))
        assertEquals("cccc, d MMM", icu.toPattern())
    }

    @Test
    fun `time zone, clone and the JDK fallback`() {
        val icu = IcuSimpleDateFormat("HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Tokyo") }
        assertEquals("07:13", icu.format(java.util.Date(1_700_000_000_000)))
        assertEquals("07:13", (icu.clone() as SimpleDateFormat).format(java.util.Date(1_700_000_000_000)))
        val symbols = IcuSimpleDateFormat("yyyy", java.text.DateFormatSymbols(Locale.US))
        assertEquals("yyyy", symbols.toPattern())
    }
}
