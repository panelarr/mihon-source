// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a compiled (binary) Android XML document, enough for test AndroidManifest.xml
 * files: one namespace, elements with string, int and boolean attributes.
 */
class BinaryXmlWriter {
    class Element(
        val name: String,
        val attributes: List<Attribute> = emptyList(),
        val children: List<Element> = emptyList(),
    )

    sealed interface Attribute {
        val name: String
        val android: Boolean

        data class Str(
            override val name: String,
            val value: String,
            override val android: Boolean = true,
        ) : Attribute

        data class Int(
            override val name: String,
            val value: kotlin.Int,
            override val android: Boolean = true,
        ) : Attribute

        data class Bool(
            override val name: String,
            val value: Boolean,
            override val android: Boolean = true,
        ) : Attribute
    }

    fun write(root: Element): ByteArray {
        // Attribute names with resource ids come first in the pool, matching the resource map.
        val androidNames = linkedSetOf<String>()
        val otherStrings = linkedSetOf(ANDROID_PREFIX, ANDROID_NS)

        fun collect(element: Element) {
            otherStrings += element.name
            element.attributes.forEach { attr ->
                if (attr.android) androidNames += attr.name else otherStrings += attr.name
                if (attr is Attribute.Str) otherStrings += attr.value
            }
            element.children.forEach(::collect)
        }
        collect(root)
        val strings = androidNames.toList() + (otherStrings - androidNames).toList()
        val index = strings.withIndex().associate { it.value to it.index }

        val body = ByteArrayOutputStream()
        body.write(stringPool(strings))
        body.write(resourceMap(androidNames.map { RESOURCE_IDS[it] ?: error("no resource id for android:$it") }))
        body.write(chunk(TYPE_NS_START, 16, ints(1, -1, index.getValue(ANDROID_PREFIX), index.getValue(ANDROID_NS))))
        writeElement(root, index, body)
        body.write(chunk(TYPE_NS_END, 16, ints(1, -1, index.getValue(ANDROID_PREFIX), index.getValue(ANDROID_NS))))
        return chunk(TYPE_XML, 8, body.toByteArray())
    }

    private fun writeElement(
        element: Element,
        index: Map<String, kotlin.Int>,
        out: ByteArrayOutputStream,
    ) {
        val nsIndex = index.getValue(ANDROID_NS)
        val attrs = ByteArrayOutputStream()
        // The binary format requires attributes sorted by resource id.
        element.attributes
            .sortedBy { if (it.android) RESOURCE_IDS.getValue(it.name) else Int.MAX_VALUE }
            .forEach { attr ->
                val ns = if (attr.android) nsIndex else -1
                val name = index.getValue(attr.name)
                val (raw, type, data) =
                    when (attr) {
                        is Attribute.Str -> Triple(index.getValue(attr.value), TYPE_STRING, index.getValue(attr.value))
                        is Attribute.Int -> Triple(-1, TYPE_INT_DEC, attr.value)
                        is Attribute.Bool -> Triple(-1, TYPE_INT_BOOLEAN, if (attr.value) -1 else 0)
                    }
                attrs.write(ints(ns, name, raw))
                attrs.write(shortsAndBytes(VALUE_SIZE, 0, type))
                attrs.write(ints(data))
            }
        val header =
            ByteBuffer
                .allocate(ATTR_EXT_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(-1)
                .putInt(index.getValue(element.name))
                .putShort(ATTR_EXT_SIZE.toShort())
                .putShort(ATTR_SIZE.toShort())
                .putShort(element.attributes.size.toShort())
                .putShort(0)
                .putShort(0)
                .putShort(0)
                .array()
        out.write(chunk(TYPE_START_ELEMENT, 16, ints(1, -1) + header + attrs.toByteArray()))
        element.children.forEach { writeElement(it, index, out) }
        out.write(chunk(TYPE_END_ELEMENT, 16, ints(1, -1, -1, index.getValue(element.name))))
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = mutableListOf<kotlin.Int>()
        strings.forEach { s ->
            offsets += data.size()
            val buffer = ByteBuffer.allocate(2 + s.length * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(s.length.toShort())
            s.forEach { buffer.putChar(it) }
            buffer.putShort(0)
            data.write(buffer.array())
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val header =
            ByteBuffer
                .allocate(20)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(strings.size)
                .putInt(0)
                .putInt(0) // UTF-16, unsorted
                .putInt(stringsStart)
                .putInt(0)
                .array()
        return chunk(TYPE_STRING_POOL, headerSize, header + ints(*offsets.toIntArray()) + data.toByteArray())
    }

    private fun resourceMap(ids: List<kotlin.Int>): ByteArray = chunk(TYPE_RESOURCE_MAP, 8, ints(*ids.toIntArray()))

    /** A chunk: type, header size, total size, then [headerRestAndBody]. */
    private fun chunk(
        type: kotlin.Int,
        headerSize: kotlin.Int,
        headerRestAndBody: ByteArray,
    ): ByteArray {
        val size = 8 + headerRestAndBody.size
        return ByteBuffer
            .allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort())
            .putShort(headerSize.toShort())
            .putInt(size)
            .put(headerRestAndBody)
            .array()
    }

    private fun ints(vararg values: kotlin.Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun shortsAndBytes(
        size: kotlin.Int,
        res0: kotlin.Int,
        type: kotlin.Int,
    ): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(size.toShort())
            .put(res0.toByte())
            .put(type.toByte())
            .array()

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val ANDROID_PREFIX = "android"
        const val TYPE_XML = 0x0003
        const val TYPE_STRING_POOL = 0x0001
        const val TYPE_RESOURCE_MAP = 0x0180
        const val TYPE_NS_START = 0x0100
        const val TYPE_NS_END = 0x0101
        const val TYPE_START_ELEMENT = 0x0102
        const val TYPE_END_ELEMENT = 0x0103
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_INT_BOOLEAN = 0x12
        const val VALUE_SIZE = 8
        const val ATTR_EXT_SIZE = 20
        const val ATTR_SIZE = 20

        val RESOURCE_IDS =
            mapOf(
                "label" to 0x01010001,
                "name" to 0x01010003,
                "value" to 0x01010024,
                "versionCode" to 0x0101021b,
                "versionName" to 0x0101021c,
                "required" to 0x0101028e,
            )
    }
}
