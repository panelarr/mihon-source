// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.util.Base64
import android.util.Base64InputStream
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Base64Test {
    private val bytes = ByteArray(100) { (it * 37).toByte() }

    @Test
    fun `default wraps at 76 chars and ends with a newline`() {
        val encoded = Base64.encodeToString(bytes, Base64.DEFAULT)
        val lines = encoded.split('\n')
        assertEquals(76, lines[0].length)
        assertTrue(encoded.endsWith("\n"))
        assertEquals("", lines.last())
        assertTrue(Base64.encodeToString(bytes, Base64.CRLF).contains("\r\n"))
    }

    @Test
    fun `flags change the alphabet, padding and wrapping`() {
        val data = byteArrayOf(-5, -1, 0x3e)
        assertEquals("+/8+", Base64.encodeToString(data, Base64.NO_WRAP))
        assertEquals("-_8-", Base64.encodeToString(data, Base64.NO_WRAP or Base64.URL_SAFE))
        assertEquals("YQ==", Base64.encodeToString("a".toByteArray(), Base64.NO_WRAP))
        assertEquals("YQ", Base64.encodeToString("a".toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING))
        assertEquals("YQ\n", Base64.encodeToString("a".toByteArray(), Base64.NO_PADDING))
    }

    @Test
    fun `decoding is lenient`() {
        val encoded = Base64.encodeToString(bytes, Base64.DEFAULT)
        assertContentEquals(bytes, Base64.decode(encoded, Base64.DEFAULT))
        assertContentEquals("a".toByteArray(), Base64.decode("YQ", Base64.DEFAULT))
        assertContentEquals(byteArrayOf(-5, -1, 0x3e), Base64.decode("-_8-", Base64.DEFAULT))
        assertContentEquals(byteArrayOf(-5, -1, 0x3e), Base64.decode(" +/8+ \n", Base64.URL_SAFE))
        assertContentEquals("hi".toByteArray(), Base64.decode("aGk=".toByteArray(), Base64.DEFAULT))
    }

    @Test
    fun `input stream decodes`() {
        val encoded = Base64.encodeToString(bytes, Base64.DEFAULT).byteInputStream()
        assertContentEquals(bytes, Base64InputStream(encoded, Base64.DEFAULT).readBytes())
    }
}
