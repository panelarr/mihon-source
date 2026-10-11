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
            merge(classes, apk, jar)
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
    ) {
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            ZipFile(classes.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
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
