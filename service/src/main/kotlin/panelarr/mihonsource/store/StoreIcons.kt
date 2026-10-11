// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.store

import panelarr.mihonsource.extension.ExtensionIcons
import panelarr.mihonsource.extension.Signatures
import panelarr.mihonsource.net.Downloader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Icons of extensions offered by stores, fetched through the service on first use and
 * kept in `cache/store-icons/` for a week. Failures are remembered briefly so a missing
 * icon isn't fetched on every page view.
 */
class StoreIcons(
    private val dir: Path,
    private val stores: StoreManager,
    private val downloader: Downloader,
    private val clock: () -> Instant = Instant::now,
) {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    suspend fun get(
        storeId: String,
        pkgName: String,
    ): ExtensionIcons.Icon? {
        val url = runCatching { stores.iconUrl(storeId, pkgName) }.getOrNull() ?: return null
        val file = dir.resolve(Signatures.sha256Hex(url.toByteArray()))
        if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toInstant().isAfter(clock().minus(TTL))) {
            val bytes = Files.readAllBytes(file)
            return ExtensionIcons.contentType(bytes)?.let { ExtensionIcons.Icon(bytes, it) }
        }
        if (failures[url]?.isAfter(clock().minus(FAILURE_TTL)) == true) return null
        val bytes = fetch(url)
        if (bytes == null) {
            failures[url] = clock()
            return null
        }
        Files.createDirectories(dir)
        Files.write(file, bytes)
        return ExtensionIcons.Icon(bytes, ExtensionIcons.contentType(bytes)!!)
    }

    /** Downloads an icon, or null when it can't be fetched or isn't an image. */
    suspend fun fetch(url: String): ByteArray? =
        runCatching { downloader.fetch(url, ExtensionIcons.MAX_BYTES.toLong()) }
            .getOrNull()
            ?.takeIf { ExtensionIcons.contentType(it) != null }

    private companion object {
        val TTL: Duration = Duration.ofDays(7)
        val FAILURE_TTL: Duration = Duration.ofMinutes(10)
    }
}
