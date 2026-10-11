// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import panelarr.mihonsource.fixtures.BinaryXmlWriter.Attribute
import panelarr.mihonsource.fixtures.BinaryXmlWriter.Element
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a signed extension APK from a jar of JVM classes, the way an Android build
 * would: D8 turns the classes into dex, and the manifest carries the extension metadata.
 */
object TestApkBuilder {
    data class Manifest(
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val label: String,
        val extensionClass: String,
        val nsfw: Boolean = false,
        val withExtensionFeature: Boolean = true,
    )

    fun build(
        classesJar: Path,
        manifest: Manifest,
        key: TestSigningKey,
        out: Path,
        assets: Map<String, ByteArray> = emptyMap(),
    ): Path {
        val work = Files.createTempDirectory("apk")
        try {
            val dexDir = Files.createDirectories(work.resolve("dex"))
            D8.run(
                D8Command
                    .builder()
                    .addProgramFiles(classesJar)
                    .setMinApiLevel(MIN_API)
                    .setDisableDesugaring(true)
                    .setOutput(dexDir, OutputMode.DexIndexed)
                    .build(),
            )
            val unsigned = work.resolve("unsigned.apk")
            ZipOutputStream(Files.newOutputStream(unsigned)).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(manifestXml(manifest))
                zip.closeEntry()
                Files.list(dexDir).use { files ->
                    files.sorted().forEach { dex ->
                        zip.putNextEntry(ZipEntry(dex.fileName.toString()))
                        Files.copy(dex, zip)
                        zip.closeEntry()
                    }
                }
                assets.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry("assets/$name"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val signer =
                ApkSigner.SignerConfig
                    .Builder("test", KeyConfig.Jca(key.keyPair.private), listOf(key.certificate))
                    .build()
            ApkSigner
                .Builder(listOf(signer))
                .setInputApk(unsigned.toFile())
                .setOutputApk(out.toFile())
                .setMinSdkVersion(MIN_API)
                .build()
                .sign()
            return out
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    fun manifestXml(manifest: Manifest): ByteArray {
        val meta =
            buildList {
                add(metaData("tachiyomi.extension.class", manifest.extensionClass))
                add(metaData("tachiyomi.extension.nsfw", if (manifest.nsfw) "1" else "0"))
            }
        val features =
            if (manifest.withExtensionFeature) {
                listOf(Element("uses-feature", listOf(Attribute.Str("name", "tachiyomi.extension"), Attribute.Bool("required", true))))
            } else {
                emptyList()
            }
        val root =
            Element(
                "manifest",
                listOf(
                    Attribute.Str("package", manifest.packageName, android = false),
                    Attribute.Int("versionCode", manifest.versionCode),
                    Attribute.Str("versionName", manifest.versionName),
                ),
                features + Element("application", listOf(Attribute.Str("label", manifest.label)), meta),
            )
        return BinaryXmlWriter().write(root)
    }

    private fun metaData(
        name: String,
        value: String,
    ) = Element("meta-data", listOf(Attribute.Str("name", name), Attribute.Str("value", value)))

    private const val MIN_API = 21
}
