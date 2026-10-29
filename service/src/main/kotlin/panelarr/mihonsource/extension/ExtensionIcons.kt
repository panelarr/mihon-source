// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import panelarr.mihonsource.storage.DurableFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Extension icons, kept as `extensions/<package>/icon`. They come from the extension file
 * itself when it carries a launcher icon under its usual resource name, else from the
 * store the extension was installed from.
 */
class ExtensionIcons(
    private val dir: Path,
) {
    fun read(pkgName: String): Icon? {
        val file = file(pkgName)
        if (!Files.isRegularFile(file)) return null
        val bytes = Files.readAllBytes(file)
        return Icon(bytes, contentType(bytes) ?: return null)
    }

    fun has(pkgName: String): Boolean = Files.isRegularFile(file(pkgName))

    /** Stores [bytes] as the icon of [pkgName] when they're a reasonable image. Returns whether they were. */
    fun save(
        pkgName: String,
        bytes: ByteArray,
    ): Boolean {
        if (bytes.size > MAX_BYTES || contentType(bytes) == null) return false
        DurableFiles.write(file(pkgName), bytes)
        return true
    }

    private fun file(pkgName: String): Path = dir.resolve(PackageNames.require(pkgName)).resolve("icon")

    data class Icon(
        val bytes: ByteArray,
        val contentType: String,
    )

    companion object {
        const val MAX_BYTES = 512 * 1024
        private val LAUNCHER = Regex("res/mipmap-([a-z]+)(-v\\d+)?/ic_launcher\\.(png|webp)")
        private val DENSITIES = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")

        /** The highest-density launcher icon in an APK or jar, when it uses the usual name. */
        fun extract(archive: Path): ByteArray? =
            runCatching {
                ZipFile(archive.toFile()).use { zip ->
                    val best =
                        zip
                            .entries()
                            .asSequence()
                            .mapNotNull { entry -> LAUNCHER.matchEntire(entry.name)?.let { entry to DENSITIES.indexOf(it.groupValues[1]) } }
                            .filter { it.second >= 0 }
                            .minByOrNull { it.second }
                            ?.first
                    best?.takeIf { it.size in 1..MAX_BYTES }?.let { entry -> zip.getInputStream(entry).use { it.readAllBytes() } }
                }
            }.getOrNull()

        /** The image type from the file's magic bytes, or null when it isn't PNG, WebP, JPEG or GIF. */
        fun contentType(bytes: ByteArray): String? {
            fun starts(prefix: ByteArray) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
            return when {
                starts(PNG) -> "image/png"
                starts(JPEG) -> "image/jpeg"
                starts(GIF) -> "image/gif"
                bytes.size >= WEBP_HEADER && starts(RIFF) && String(bytes, RIFF_TYPE_OFFSET, RIFF.size) == "WEBP" -> "image/webp"
                else -> null
            }
        }

        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val GIF = "GIF8".toByteArray()
        private val RIFF = "RIFF".toByteArray()
        private const val RIFF_TYPE_OFFSET = 8
        private const val WEBP_HEADER = 12
    }
}
