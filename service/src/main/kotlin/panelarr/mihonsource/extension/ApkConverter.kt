// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import com.googlecode.d2j.dex.Dex2jar
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Turns an extension APK into a jar the JVM can load: its dex code is translated with
 * dex2jar, and its `assets/` are copied in, because extensions read some assets as
 * classpath resources.
 */
object ApkConverter {
    fun convert(
        apk: Path,
        jar: Path,
        host: ClassLoader = ApkConverter::class.java.classLoader,
    ) {
        val classes = Files.createTempFile(jar.parent, "classes", ".jar")
        try {
            Files.delete(classes)
            Dex2jar
                .from(apk.toFile())
                .topoLogicalSort()
                .skipDebug(false)
                .noCode(false)
                .skipExceptions(false)
                .to(classes)
            merge(classes, apk, jar, host)
        } catch (e: java.io.IOException) {
            throw ExtensionException("couldn't convert the APK: ${e.message}", e)
        } finally {
            Files.deleteIfExists(classes)
        }
    }

    private fun merge(
        classes: Path,
        apk: Path,
        jar: Path,
        host: ClassLoader,
    ) {
        val classBytes =
            ZipFile(classes.toFile()).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .associate { it.name.removeSuffix(".class") to zip.getInputStream(it).use { input -> input.readAllBytes() } }
            }
        // dex2jar writes no stack map frames; the JVM's verifier needs them.
        val frames = FrameComputer(classBytes, host)
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            classBytes.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry("$name.class"))
                out.write(frames.compute(bytes))
                out.closeEntry()
            }
            ZipFile(apk.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith("assets/")) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }
}
