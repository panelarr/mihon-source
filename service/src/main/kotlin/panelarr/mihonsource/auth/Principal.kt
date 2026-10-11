// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.auth

/** Who made a request. */
sealed interface Principal {
    val name: String
    val scopes: Set<String>

    /** The holder of the admin token. */
    data object Admin : Principal {
        override val name = "admin"
        override val scopes: Set<String> = setOf(Scopes.ADMIN)
    }

    /** A client authenticated with its API key. */
    data class Client(
        val id: String,
        override val name: String,
        override val scopes: Set<String>,
    ) : Principal
}
