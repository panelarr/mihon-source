// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import kotlinx.serialization.Serializable

/** A challenge solver endpoint (FlareSolverr-compatible API). */
@Serializable
data class SolverSettings(
    val id: String,
    val name: String,
    val type: Type,
    val url: String,
    val sessionTtlSeconds: Long? = null,
    val maxTimeoutSeconds: Long? = null,
    val asResponseFallback: Boolean = false,
) {
    enum class Type { FLARESOLVERR, BYPARR }
}

@Serializable
data class ProxySettings(
    /** http://, https:// or socks5:// URL without credentials. */
    val url: String,
    val username: String? = null,
    /** Encrypted with the service's secret key; never plain text on disk. */
    val sealedPassword: String? = null,
)

@Serializable
data class RateLimitSettings(
    val requests: Int,
    val periodMillis: Long,
    val burst: Int = 0,
    val perHost: Boolean = false,
)

enum class UserAgentMode { DEFAULT, FIXED, RANDOM_DESKTOP, FROM_SOLVER }

/** A named network profile managed through the admin API. */
@Serializable
data class ProfileSettings(
    val id: String,
    val name: String,
    val proxy: ProxySettings? = null,
    val solverId: String? = null,
    val rateLimit: RateLimitSettings? = null,
    val concurrency: Int = 0,
    val userAgentMode: UserAgentMode = UserAgentMode.DEFAULT,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val sharedCookieJar: String? = null,
    val connectTimeoutMillis: Long? = null,
    val readTimeoutMillis: Long? = null,
    val callTimeoutMillis: Long? = null,
)

/**
 * A profile pushed by a host for one source. It carries its solver inline, because the
 * host's solvers aren't known here.
 */
@Serializable
data class PushedProfile(
    val profile: ProfileSettings,
    val solver: SolverSettings? = null,
)

/** Everything managed through the integrations API, as stored in one file. */
@Serializable
data class IntegrationsState(
    val solvers: List<SolverSettings> = emptyList(),
    val profiles: List<ProfileSettings> = emptyList(),
    /** Source id (as a string) to profile id. */
    val assignments: Map<String, String> = emptyMap(),
    val defaultProfileId: String? = null,
)
