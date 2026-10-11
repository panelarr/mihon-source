// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import java.nio.file.Path

/** Reads what a jar says about itself. Filled in once the jar layout is settled. */
class JarDescriptorReader {
    fun read(
        jar: Path,
        hint: ExtensionDescriptor?,
    ): ExtensionDescriptor = hint ?: throw ExtensionException("the jar doesn't describe itself: ${jar.fileName}")
}
