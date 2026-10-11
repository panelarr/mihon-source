package panelarr.mihonsource.auth

/** Permission scopes a client API key can hold. */
object Scopes {
    /** Browse sources: every read of the source API. */
    const val SOURCES_READ = "sources:read"

    /** Change source settings and push network profiles. */
    const val SOURCES_WRITE = "sources:write"

    /** Everything, including the admin API. */
    const val ADMIN = "admin"

    val all: Set<String> = linkedSetOf(SOURCES_READ, SOURCES_WRITE, ADMIN)

    /** Whether [granted] allows a call that needs [required]. */
    fun allows(
        granted: Set<String>,
        required: String,
    ): Boolean = ADMIN in granted || required in granted || (required == SOURCES_READ && SOURCES_WRITE in granted)
}
