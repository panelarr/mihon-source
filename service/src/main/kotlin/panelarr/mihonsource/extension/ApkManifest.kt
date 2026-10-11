// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import com.android.apksig.internal.apk.AndroidBinXmlParser
import java.nio.ByteBuffer

/** The parts of an extension's AndroidManifest.xml the loader needs. */
data class ApkManifest(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val label: String?,
    val features: Set<String>,
    /** `<meta-data>` entries of the application, name to string value. */
    val metaData: Map<String, String>,
) {
    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        // Resource ids of the android: attributes, used when an APK strips attribute names.
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_LABEL = 0x01010001
        private const val ATTR_VALUE = 0x01010024
        private const val ATTR_VERSION_CODE = 0x0101021b
        private const val ATTR_VERSION_NAME = 0x0101021c
        private const val ATTR_VERSION_CODE_MAJOR = 0x01010576

        /** Parses a binary (compiled) AndroidManifest.xml. */
        fun parse(binaryXml: ByteArray): ApkManifest {
            val parser = AndroidBinXmlParser(ByteBuffer.wrap(binaryXml))
            var packageName: String? = null
            var versionName = ""
            var versionCode = 0L
            var versionCodeMajor = 0L
            var label: String? = null
            val features = linkedSetOf<String>()
            val metaData = linkedMapOf<String, String>()
            val path = ArrayDeque<String>()

            var event = parser.eventType
            while (event != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                when (event) {
                    AndroidBinXmlParser.EVENT_START_ELEMENT -> {
                        path.addLast(parser.name)
                        val attrs = Attributes(parser)
                        when (path.joinToString("/")) {
                            "manifest" -> {
                                packageName = attrs.plain("package")
                                versionName = attrs.android("versionName", ATTR_VERSION_NAME) ?: ""
                                versionCode = attrs.androidInt("versionCode", ATTR_VERSION_CODE) ?: 0
                                versionCodeMajor = attrs.androidInt("versionCodeMajor", ATTR_VERSION_CODE_MAJOR) ?: 0
                            }

                            "manifest/uses-feature" -> {
                                attrs.android("name", ATTR_NAME)?.let(features::add)
                            }

                            "manifest/application" -> {
                                label = attrs.android("label", ATTR_LABEL)
                            }

                            "manifest/application/meta-data" -> {
                                val name = attrs.android("name", ATTR_NAME)
                                val value = attrs.android("value", ATTR_VALUE)
                                if (name != null && value != null) metaData[name] = value
                            }
                        }
                    }

                    AndroidBinXmlParser.EVENT_END_ELEMENT -> {
                        path.removeLastOrNull()
                    }
                }
                event = parser.next()
            }
            return ApkManifest(
                packageName = requireNotNull(packageName) { "the manifest has no package name" },
                versionName = versionName,
                versionCode = (versionCodeMajor shl Int.SIZE_BITS) or versionCode,
                label = label,
                features = features,
                metaData = metaData,
            )
        }

        /** Reads attributes by android: name, falling back to the resource id. */
        private class Attributes(
            private val parser: AndroidBinXmlParser,
        ) {
            private fun index(
                name: String,
                resourceId: Int?,
                namespace: String,
            ): Int? =
                (0 until parser.attributeCount).firstOrNull { i ->
                    (parser.getAttributeName(i) == name && parser.getAttributeNamespace(i) == namespace) ||
                        (resourceId != null && parser.getAttributeNameResourceId(i) == resourceId)
                }

            fun plain(name: String): String? = index(name, null, "")?.let(::stringValue)

            fun android(
                name: String,
                resourceId: Int,
            ): String? = index(name, resourceId, ANDROID_NS)?.let(::stringValue)

            fun androidInt(
                name: String,
                resourceId: Int,
            ): Long? =
                index(name, resourceId, ANDROID_NS)?.let { i ->
                    when (parser.getAttributeValueType(i)) {
                        AndroidBinXmlParser.VALUE_TYPE_INT -> parser.getAttributeIntValue(i).toLong() and UNSIGNED_INT_MASK
                        else -> parser.getAttributeStringValue(i).toLongOrNull()
                    }
                }

            private fun stringValue(i: Int): String? =
                when (parser.getAttributeValueType(i)) {
                    AndroidBinXmlParser.VALUE_TYPE_STRING -> parser.getAttributeStringValue(i)

                    AndroidBinXmlParser.VALUE_TYPE_INT -> parser.getAttributeIntValue(i).toString()

                    AndroidBinXmlParser.VALUE_TYPE_BOOLEAN -> parser.getAttributeBooleanValue(i).toString()

                    // A reference (e.g. @string/app_name) can't be resolved without resources.
                    else -> null
                }
        }

        private const val UNSIGNED_INT_MASK = 0xffffffffL
    }
}
