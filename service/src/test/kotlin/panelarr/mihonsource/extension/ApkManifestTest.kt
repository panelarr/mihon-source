// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import panelarr.mihonsource.fixtures.TestApkBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkManifestTest {
    private val manifest =
        TestApkBuilder.Manifest(
            packageName = "eu.kanade.tachiyomi.extension.en.example",
            versionName = "1.4.7",
            versionCode = 7,
            label = "Tachiyomi: Example",
            extensionClass = ".Example;org.example.Other",
            nsfw = true,
        )

    @Test
    fun `binary manifest is parsed`() {
        val parsed = ApkManifest.parse(TestApkBuilder.manifestXml(manifest))
        assertEquals(manifest.packageName, parsed.packageName)
        assertEquals("1.4.7", parsed.versionName)
        assertEquals(7, parsed.versionCode)
        assertEquals("Tachiyomi: Example", parsed.label)
        assertTrue("tachiyomi.extension" in parsed.features)
        assertEquals(".Example;org.example.Other", parsed.metaData["tachiyomi.extension.class"])
    }

    @Test
    fun `descriptor resolves class names, lib version and nsfw`() {
        val descriptor = ManifestDescriptors.fromManifest(ApkManifest.parse(TestApkBuilder.manifestXml(manifest)))
        assertEquals("Example", descriptor.name)
        assertEquals("1.4", descriptor.libVersion)
        assertEquals(listOf("eu.kanade.tachiyomi.extension.en.example.Example", "org.example.Other"), descriptor.classNames)
        assertTrue(descriptor.nsfw)
    }

    @Test
    fun `apps without the extension feature are rejected`() {
        val parsed = ApkManifest.parse(TestApkBuilder.manifestXml(manifest.copy(withExtensionFeature = false)))
        assertFailsWith<ExtensionException> { ManifestDescriptors.fromManifest(parsed) }
    }

    @Test
    fun `lib versions outside 1_4 to 1_6 are rejected`() {
        assertTrue(LibVersion.isSupported("1.4"))
        assertTrue(LibVersion.isSupported("1.5"))
        assertTrue(LibVersion.isSupported("1.6"))
        listOf("1.3", "1.7", "2.0", "x").forEach { assertEquals(false, LibVersion.isSupported(it), it) }
        assertEquals("1.6", LibVersion.fromVersionName("1.6.12"))
    }
}
