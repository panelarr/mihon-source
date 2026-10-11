// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import panelarr.mihonsource.net.Downloader
import java.nio.file.Files

/**
 * Entry points for installing extensions from a URL or from bytes, and for removing them
 * along with their data.
 */
class ExtensionInstaller(
    private val manager: ExtensionManager,
    private val downloader: Downloader,
    private val maxBytes: Long,
    /** Deletes what the service keeps per source (settings, cookies). */
    private val deleteSourceData: (sourceIds: List<Long>) -> Unit = {},
) {
    suspend fun installFromUrl(
        url: String,
        expectations: Expectations,
        origin: ExtensionRecord.Origin = ExtensionRecord.Origin.Url(Downloader.validate(url)),
        hint: ExtensionDescriptor? = null,
        contentWarning: ExtensionRecord.ContentWarning? = null,
    ): InstalledExtension {
        val bytes = downloader.fetch(url, maxBytes)
        return install(bytes, origin, expectations, hint, contentWarning)
    }

    suspend fun installFromBytes(
        bytes: ByteArray,
        fileName: String,
        expectations: Expectations,
    ): InstalledExtension {
        require(bytes.isNotEmpty()) { "the uploaded file is empty" }
        require(bytes.size <= maxBytes) { "the file is too large (limit $maxBytes bytes)" }
        val name =
            fileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .take(MAX_FILE_NAME)
                .ifBlank { "upload" }
        return install(bytes, ExtensionRecord.Origin.Upload(name), expectations, null, null)
    }

    suspend fun uninstall(
        pkgName: String,
        deleteData: Boolean,
    ) {
        val removed = manager.uninstall(pkgName)
        if (deleteData) deleteSourceData(removed.map { it.id })
    }

    private suspend fun install(
        bytes: ByteArray,
        origin: ExtensionRecord.Origin,
        expectations: Expectations,
        hint: ExtensionDescriptor?,
        contentWarning: ExtensionRecord.ContentWarning?,
    ): InstalledExtension {
        val file = manager.newIncomingFile()
        Files.write(file, bytes)
        return manager.install(file, origin, expectations, hint, contentWarning)
    }

    private companion object {
        const val MAX_FILE_NAME = 200
    }
}
