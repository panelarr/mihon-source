// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes that survive a crash or a power cut. Renaming a finished temporary file over the
 * old one isn't enough on its own: after a hard stop the file system can keep the rename
 * but not the data, leaving a file of the right size that holds only zeros. So the data is
 * flushed to disk before the rename, and the directory after it.
 */
object DurableFiles {
    /** Replaces [target] with [bytes] in one step. [private] restricts the file to its owner. */
    fun write(
        target: Path,
        bytes: ByteArray,
        private: Boolean = false,
    ) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        FileChannel
            .open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            .use { channel ->
                if (private) restrictToOwner(tmp)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(target.parent)
    }

    /** Flushes what has been written to [file] to disk. */
    fun sync(file: Path) {
        FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
    }

    /**
     * Flushes [dir]'s entries to disk, so a file created or renamed in it is still there
     * after a crash. Not every platform can open a directory for this; there it's skipped.
     */
    fun syncDirectory(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Not supported here (e.g. on Windows); the rename is still atomic.
        }
    }

    private fun restrictToOwner(file: Path) {
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }
}
