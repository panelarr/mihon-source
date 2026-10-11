// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

/** An extension can't be installed or loaded. The message is meant for the admin. */
class ExtensionException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
