// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Whether code can run from files in [dir]: false on a noexec mount, where the native
 * zstd decoder extensions' HTTP clients unpack there can't be loaded.
 */
internal fun runsCode(dir: Path): Boolean =
    try {
        val probe = Files.createTempFile(dir, ".exec-probe", "")
        try {
            Files.setPosixFilePermissions(probe, PosixFilePermissions.fromString("rwx------"))
            Files.isExecutable(probe)
        } finally {
            Files.deleteIfExists(probe)
        }
    } catch (
        @Suppress("SwallowedException") e: IOException,
    ) {
        false
    } catch (
        @Suppress("SwallowedException") e: UnsupportedOperationException,
    ) {
        true // Not a POSIX file system: no noexec mounts to worry about.
    }

/** Warns when java.io.tmpdir doesn't allow running code, and says how to fix it. */
internal fun checkTempDir() {
    val dir = Path.of(System.getProperty("java.io.tmpdir"))
    if (!runsCode(dir)) {
        LoggerFactory.getLogger("panelarr.mihonsource").warn(
            "The temporary directory {} doesn't allow running code (noexec, or not writable). Sites that answer " +
                "with zstd compression will fail. Mount it with exec, or start with -Djava.io.tmpdir=<a directory that " +
                "allows it>; the container image does this itself (MIHON_TMP_DIR).",
            dir,
        )
    }
}
