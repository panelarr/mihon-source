// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extensionapi

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource

/**
 * Hooks the service installs into the extension API at start. Extensions can't see this
 * package (it isn't shared with their classloaders).
 */
object ExtensionHost {
    @Volatile
    private var networks: ((HttpSource) -> NetworkHelper)? = null

    /** Sets how sources get their network helper. */
    fun install(networkFor: (HttpSource) -> NetworkHelper) {
        networks = networkFor
    }

    fun networkFor(source: HttpSource): NetworkHelper = checkNotNull(networks) { "the extension host isn't set up" }.invoke(source)
}
