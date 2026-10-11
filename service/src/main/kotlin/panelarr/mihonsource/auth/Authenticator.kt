package panelarr.mihonsource.auth

/** Turns a bearer token into a [Principal]. */
class Authenticator(
    private val adminToken: AdminToken,
    private val clients: ClientRegistry,
) {
    fun authenticate(token: String): Principal? =
        when {
            adminToken.matches(token) -> Principal.Admin
            else -> clients.authenticate(token)
        }

    companion object {
        private const val BEARER = "bearer "

        /** The token of an `Authorization: Bearer <token>` header value, or null. */
        fun bearerToken(header: String?): String? {
            if (header == null || header.length <= BEARER.length) return null
            if (!header.regionMatches(0, BEARER, 0, BEARER.length, ignoreCase = true)) return null
            return header.substring(BEARER.length).trim().takeIf { it.isNotEmpty() }
        }
    }
}
