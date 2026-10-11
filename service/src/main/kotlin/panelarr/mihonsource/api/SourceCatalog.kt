// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import panelarr.source.v1.Source

/** The sources this service serves. Extensions add theirs once they're loaded. */
fun interface SourceCatalog {
    fun sources(): List<Source>
}
