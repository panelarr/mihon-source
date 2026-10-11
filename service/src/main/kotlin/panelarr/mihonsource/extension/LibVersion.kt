// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

/** extensions-lib versions this host can run. */
object LibVersion {
    /** Oldest supported, inclusive. */
    const val MIN = "1.4"

    /** Newest supported, inclusive. */
    const val MAX = "1.6"

    /** The lib version part of an extension's version name: "1.4.12" gives "1.4". */
    fun fromVersionName(versionName: String): String {
        val parts = versionName.split('.')
        require(parts.size >= 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
            "'$versionName' isn't an extension version name (expected <lib major>.<lib minor>.<version>)"
        }
        return "${parts[0]}.${parts[1]}"
    }

    fun isSupported(libVersion: String): Boolean {
        val version = parse(libVersion) ?: return false
        return version >= parse(MIN)!! && version <= parse(MAX)!!
    }

    fun requireSupported(libVersion: String) {
        if (!isSupported(libVersion)) {
            throw ExtensionException("extensions-lib $libVersion isn't supported (supported: $MIN to $MAX)")
        }
    }

    private fun parse(version: String): Int? {
        val (major, minor) = version.split('.').takeIf { it.size == 2 } ?: return null
        val majorInt = major.toIntOrNull() ?: return null
        val minorInt = minor.toIntOrNull() ?: return null
        return majorInt * MINOR_RANGE + minorInt
    }

    private const val MINOR_RANGE = 1000
}
