// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphicsTest {
    /** A bitmap where every pixel is unique, so any misplaced pixel shows. */
    private fun pattern(
        width: Int,
        height: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, Color.argb(255, x * 7 % 256, y * 5 % 256, (x + y) % 256))
        return bitmap
    }

    private fun png(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        return out.toByteArray()
    }

    @Test
    fun `png round-trips exactly through compress and decode`() {
        val original = pattern(40, 30)
        val bytes = png(original)
        val decoded = assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        assertTrue(original.sameAs(decoded))
        assertTrue(original.sameAs(BitmapFactory.decodeStream(bytes.inputStream())))

        val padded = byteArrayOf(1, 2, 3) + bytes
        assertTrue(original.sameAs(BitmapFactory.decodeByteArray(padded, 3, bytes.size)))
    }

    @Test
    fun `bounds-only decoding and sampling fill the options`() {
        val bytes = png(pattern(64, 32))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assertNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds))
        assertEquals(64, bounds.outWidth)
        assertEquals(32, bounds.outHeight)
        assertEquals("image/png", bounds.outMimeType)

        val sampled = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 3 })
        assertEquals(32, sampled.width)
        assertEquals(16, sampled.height)

        assertNull(BitmapFactory.decodeByteArray(byteArrayOf(1, 2, 3), 0, 3))
    }

    @Test
    fun `jpeg compresses and decodes`() {
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 40, 40)) }
        val out = ByteArrayOutputStream()
        assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 90, out))
        val bytes = out.toByteArray()
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val pixel = decoded.getPixel(8, 8)
        assertTrue(kotlin.math.abs(Color.red(pixel) - 200) < 8 && Color.green(pixel) < 50)

        // WEBP output is JPEG below quality 100 and PNG at 100: ImageIO can't write WebP.
        val lossy = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.toByteArray()
        assertEquals(0xD8.toByte(), lossy[1])
        val lossless = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 80, it) }.toByteArray()
        assertEquals('P'.code.toByte(), lossless[1])
    }

    @Test
    fun `webp decodes`() {
        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(SOLID_WEBP, 0, SOLID_WEBP.size))
        assertEquals(3, bitmap.width)
        assertEquals(2, bitmap.height)
        assertEquals(Color.argb(255, 0x12, 0x34, 0x56), bitmap.getPixel(2, 1))

        val info = mutableListOf<String>()
        val decoded =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(SOLID_WEBP)) { decoder, header, _ ->
                info += header.mimeType
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        assertEquals(listOf("image/webp"), info)
        assertTrue(decoded.isMutable)
    }

    @Test
    fun `cropping copies the right pixels`() {
        val source = pattern(20, 20)
        val crop = Bitmap.createBitmap(source, 5, 6, 4, 3)
        assertEquals(4, crop.width)
        assertEquals(source.getPixel(5, 6), crop.getPixel(0, 0))
        assertEquals(source.getPixel(8, 8), crop.getPixel(3, 2))
        assertFailsWith<IllegalArgumentException> { Bitmap.createBitmap(source, 18, 0, 4, 1) }
    }

    @Test
    fun `canvas unscrambles tiles exactly`() {
        val source = pattern(40, 20)
        // Swap the left and right halves.
        val result = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(source, Rect(0, 0, 20, 20), Rect(20, 0, 40, 20), null)
        canvas.drawBitmap(source, Rect(20, 0, 40, 20), RectF(0f, 0f, 20f, 20f), null)
        for (y in 0 until 20) {
            for (x in 0 until 20) {
                assertEquals(source.getPixel(x, y), result.getPixel(x + 20, y))
                assertEquals(source.getPixel(x + 20, y), result.getPixel(x, y))
            }
        }

        val shifted = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        Canvas(shifted).apply {
            save()
            translate(10f, 0f)
            drawBitmap(source, 0f, 0f, null)
            restore()
            drawBitmap(source, Matrix().apply { postTranslate(-30f, 0f) }, Paint())
        }
        assertEquals(source.getPixel(0, 5), shifted.getPixel(10, 5))
        assertEquals(source.getPixel(35, 5), shifted.getPixel(5, 5))
    }

    @Test
    fun `pixels arrays, colors and filters`() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(16) { Color.rgb(it, it, it) }
        bitmap.setPixels(pixels, 0, 4, 0, 0, 4, 4)
        val back = IntArray(16)
        bitmap.getPixels(back, 0, 4, 0, 0, 4, 4)
        assertEquals(pixels.toList(), back.toList())

        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        canvas.drawRect(0f, 0f, 2f, 2f, Paint().apply { color = Color.RED })
        assertEquals(Color.RED, bitmap.getPixel(1, 1))
        assertEquals(Color.WHITE, bitmap.getPixel(3, 3))

        val gray = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val filter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        Canvas(gray).drawBitmap(bitmap, Rect(0, 0, 1, 1), Rect(0, 0, 1, 1), Paint().apply { colorFilter = filter })
        val p = gray.getPixel(0, 0)
        assertEquals(Color.red(p), Color.green(p))
        assertEquals(Color.parseColor("#80FF0000"), Color.argb(0x80, 255, 0, 0))

        bitmap.recycle()
        assertTrue(bitmap.isRecycled)
        assertFailsWith<IllegalStateException> { bitmap.getPixel(0, 0) }
    }

    @Test
    fun `text draws and lays out`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val paint =
            TextPaint().apply {
                isAntiAlias = true
                color = Color.BLACK
                textSize = 20f
            }
        assertTrue(paint.measureText("Hello") > 0f)
        val layout = StaticLayout("Hello world, this wraps onto several lines", paint, 120, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        assertTrue(layout.lineCount > 1)
        assertTrue(layout.height > 20)
        layout.draw(Canvas(bitmap))
        val dark = (0 until 200).sumOf { y -> (0 until 200).count { x -> Color.red(bitmap.getPixel(x, y)) < 128 } }
        assertTrue(dark > 0)
    }

    private companion object {
        /** A 3x2 lossless WebP of a single color, built bit by bit (VP8L with one-symbol codes). */
        val SOLID_WEBP: ByteArray = solidWebP(3, 2, a = 0xFF, r = 0x12, g = 0x34, b = 0x56)

        fun solidWebP(
            width: Int,
            height: Int,
            a: Int,
            r: Int,
            g: Int,
            b: Int,
        ): ByteArray {
            val bits = BitWriter()
            bits.write(width - 1, 14)
            bits.write(height - 1, 14)
            bits.write(1, 1) // alpha used
            bits.write(0, 3) // version
            bits.write(0, 1) // no transform
            bits.write(0, 1) // no color cache
            bits.write(0, 1) // no meta prefix codes
            for (symbol in listOf(g, r, b, a)) {
                bits.write(1, 1) // simple code
                bits.write(0, 1) // one symbol
                bits.write(1, 1) // 8-bit symbol
                bits.write(symbol, 8)
            }
            bits.write(1, 1) // distance code: simple, one 1-bit symbol 0
            bits.write(0, 1)
            bits.write(0, 1)
            bits.write(0, 1)
            // Trailing zero bytes: the decoder reads ahead in 64-bit words.
            val payload = byteArrayOf(0x2f) + bits.bytes() + ByteArray(8)
            val chunk = "VP8L".toByteArray() + le32(payload.size) + payload + (if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
            return "RIFF".toByteArray() + le32(4 + chunk.size) + "WEBP".toByteArray() + chunk
        }

        fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var acc = 0L
        private var count = 0

        fun write(
            value: Int,
            bits: Int,
        ) {
            acc = acc or ((value.toLong() and ((1L shl bits) - 1)) shl count)
            count += bits
            while (count >= 8) {
                out.write((acc and 0xFF).toInt())
                acc = acc ushr 8
                count -= 8
            }
        }

        fun bytes(): ByteArray {
            if (count > 0) out.write((acc and 0xFF).toInt())
            return out.toByteArray()
        }
    }
}
