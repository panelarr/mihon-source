// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The test extensions built by Gradle (test-extensions/), and ways to repackage them. */
object TestExtensions {
    private val jars: List<Path> =
        System
            .getProperty("test.extensions", "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }

    val lib16: Path get() = jar("lib16")
    val lib14: Path get() = jar("lib14")

    /** Drives a WebView; needs the browser service. */
    val webview: Path get() = jar("webview")

    const val LIB16_PKG = "panelarr.testext.lib16"
    const val LIB14_PKG = "panelarr.testext.lib14"
    const val WEBVIEW_PKG = "panelarr.testext.webview"

    private fun jar(name: String): Path =
        jars.firstOrNull { it.fileName.toString().startsWith(name) } ?: error("test extension $name not built")

    /** A v1-signed copy of [jar] (JAR signature, as extension stores publish them). */
    fun signedJar(
        jar: Path,
        key: TestSigningKey,
        out: Path,
    ): Path {
        val signer =
            ApkSigner.SignerConfig
                .Builder("CERT", KeyConfig.Jca(key.keyPair.private), listOf(key.certificate))
                .build()
        ApkSigner
            .Builder(listOf(signer))
            .setInputApk(jar.toFile())
            .setOutputApk(out.toFile())
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(false)
            .setV3SigningEnabled(false)
            .setMinSdkVersion(MIN_SDK)
            .build()
            .sign()
        return out
    }

    /** A copy of [jar] with its AndroidManifest.xml replaced through [edit]. */
    fun withManifest(
        jar: Path,
        out: Path,
        edit: (String) -> String,
    ): Path {
        ZipFile(jar.toFile()).use { zip ->
            ZipOutputStream(Files.newOutputStream(out)).use { output ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name))
                    val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                    output.write(if (entry.name == "AndroidManifest.xml") edit(String(bytes)).toByteArray() else bytes)
                    output.closeEntry()
                }
            }
        }
        return out
    }

    /** Builds a signed APK of the lib 1.4 test extension, with its assets. */
    fun lib14Apk(
        key: TestSigningKey,
        out: Path,
    ): Path {
        val assets =
            ZipFile(lib14.toFile()).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.startsWith("assets/") && !it.isDirectory }
                    .associate { it.name.removePrefix("assets/") to zip.getInputStream(it).use { s -> s.readAllBytes() } }
            }
        val classesOnly = Files.createTempFile("lib14-classes", ".jar")
        try {
            ZipFile(lib14.toFile()).use { zip ->
                ZipOutputStream(Files.newOutputStream(classesOnly)).use { output ->
                    zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { entry ->
                        output.putNextEntry(ZipEntry(entry.name))
                        zip.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            return TestApkBuilder.build(
                classesOnly,
                TestApkBuilder.Manifest(
                    packageName = LIB14_PKG,
                    versionName = "1.4.3",
                    versionCode = 104003,
                    label = "Tachiyomi: Lib14 Test",
                    extensionClass = ".Lib14Source",
                    nsfw = true,
                ),
                key,
                out,
                assets,
                mapOf("res/mipmap-mdpi/ic_launcher.png" to png(16), "res/mipmap-xxhdpi/ic_launcher.png" to png(48)),
            )
        } finally {
            Files.deleteIfExists(classesOnly)
        }
    }

    /** A small solid PNG of [size] pixels, as a test icon. */
    fun png(size: Int): ByteArray {
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private const val MIN_SDK = 26
}
