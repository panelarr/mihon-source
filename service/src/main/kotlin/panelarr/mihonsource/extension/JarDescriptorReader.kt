// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import org.w3c.dom.Element
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads what an extension jar says about itself. Extension jars carry the APK's merged
 * AndroidManifest.xml, usually as plain text (binary is accepted too). Without a
 * manifest, the caller's [hint] (e.g. from a store index) is used.
 */
class JarDescriptorReader {
    fun read(
        jar: Path,
        hint: ExtensionDescriptor?,
    ): ExtensionDescriptor {
        val bytes =
            ZipFile(jar.toFile()).use { zip ->
                zip.getEntry(MANIFEST)?.let { entry -> zip.getInputStream(entry).use { it.readAllBytes() } }
            }
        if (bytes == null) {
            return hint ?: throw ExtensionException("the jar has no $MANIFEST, so it can't be identified")
        }
        val manifest = if (isBinaryXml(bytes)) ApkManifest.parse(bytes) else parseText(bytes)
        return ManifestDescriptors.fromManifest(manifest)
    }

    /** Compiled Android XML starts with the RES_XML_TYPE chunk header. */
    private fun isBinaryXml(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 0x03.toByte() && bytes[1] == 0x00.toByte()

    private fun parseText(bytes: ByteArray): ApkManifest {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Untrusted input: no DTDs, no external entities.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isExpandEntityReferences = false
                isXIncludeAware = false
            }
        val document =
            runCatching { factory.newDocumentBuilder().parse(bytes.inputStream()) }
                .getOrElse { throw ExtensionException("the jar's $MANIFEST isn't valid XML: ${it.message}", it) }
        val root = document.documentElement
        if (root.tagName != "manifest") throw ExtensionException("the jar's $MANIFEST has no <manifest> root")

        fun Element.android(name: String): String? = getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

        fun children(
            parent: Element,
            tag: String,
        ): List<Element> =
            (0 until parent.childNodes.length)
                .map { parent.childNodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName == tag }

        val application = children(root, "application").firstOrNull()
        return ApkManifest(
            packageName = root.getAttribute("package").ifEmpty { throw ExtensionException("the manifest has no package name") },
            versionName = root.android("versionName").orEmpty(),
            versionCode = root.android("versionCode")?.toLongOrNull() ?: 0,
            label = application?.android("label"),
            features = children(root, "uses-feature").mapNotNull { it.android("name") }.toSet(),
            metaData =
                application
                    ?.let { children(it, "meta-data") }
                    .orEmpty()
                    .mapNotNull { meta -> meta.android("name")?.let { name -> meta.android("value")?.let { name to it } } }
                    .toMap(),
        )
    }

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
