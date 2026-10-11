// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source.model

import android.net.Uri

/** One page of a chapter. The default arguments are part of the binary interface. */
open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    @Transient var uri: Uri? = null,
) {
    val number: Int
        get() = index + 1

    override fun toString(): String = "Page(index=$index, url=$url, imageUrl=$imageUrl)"
}
