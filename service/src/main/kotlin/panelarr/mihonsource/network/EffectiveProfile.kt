// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import panelarr.mihonsource.secret.SecretBox
import java.net.URI
import java.time.Duration

/** The network settings a source uses right now, with secrets decrypted. */
data class EffectiveProfile(
    val origin: Origin,
    /** Local profile id (assigned or default), or the pushed profile's name. */
    val profileId: String?,
    val proxy: Proxy?,
    val solver: SolverSettings?,
    val rateLimit: RateLimitSettings?,
    val concurrency: Int,
    val userAgentMode: UserAgentMode,
    val userAgent: String?,
    val headers: Map<String, String>,
    val sharedCookieJar: String?,
    val connectTimeout: Duration?,
    val readTimeout: Duration?,
    val callTimeout: Duration?,
) {
    enum class Origin { NONE, DEFAULT, ASSIGNED, PUSHED }

    data class Proxy(
        val type: java.net.Proxy.Type,
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
    ) {
        override fun toString(): String = "Proxy($type $host:$port, auth=${username != null})"
    }

    companion object {
        val DIRECT =
            EffectiveProfile(
                origin = Origin.NONE,
                profileId = null,
                proxy = null,
                solver = null,
                rateLimit = null,
                concurrency = 0,
                userAgentMode = UserAgentMode.DEFAULT,
                userAgent = null,
                headers = emptyMap(),
                sharedCookieJar = null,
                connectTimeout = null,
                readTimeout = null,
                callTimeout = null,
            )
    }
}

/** Works out which profile applies to a source: pushed, then assigned, then default, else direct. */
class ProfileResolver(
    private val store: IntegrationsStore,
    private val secrets: SecretBox,
) {
    fun resolve(sourceId: Long): EffectiveProfile {
        store.pushed(sourceId)?.let { pushed ->
            return build(pushed.profile, pushed.solver, EffectiveProfile.Origin.PUSHED, pushed.profile.name)
        }
        val state = store.state()
        val assigned = state.assignments[sourceId.toString()]
        val (profileId, origin) =
            when {
                assigned != null -> assigned to EffectiveProfile.Origin.ASSIGNED
                state.defaultProfileId != null -> state.defaultProfileId to EffectiveProfile.Origin.DEFAULT
                else -> return EffectiveProfile.DIRECT
            }
        val profile = state.profiles.firstOrNull { it.id == profileId } ?: return EffectiveProfile.DIRECT
        val solver = profile.solverId?.let { id -> state.solvers.firstOrNull { it.id == id } }
        return build(profile, solver, origin, profile.id)
    }

    private fun build(
        profile: ProfileSettings,
        solver: SolverSettings?,
        origin: EffectiveProfile.Origin,
        id: String,
    ) = EffectiveProfile(
        origin = origin,
        profileId = id,
        proxy = profile.proxy?.let(::proxy),
        solver = solver,
        rateLimit = profile.rateLimit,
        concurrency = profile.concurrency,
        userAgentMode = profile.userAgentMode,
        userAgent = profile.userAgent,
        headers = profile.headers,
        sharedCookieJar = profile.sharedCookieJar,
        connectTimeout = profile.connectTimeoutMillis?.let(Duration::ofMillis),
        readTimeout = profile.readTimeoutMillis?.let(Duration::ofMillis),
        callTimeout = profile.callTimeoutMillis?.let(Duration::ofMillis),
    )

    private fun proxy(settings: ProxySettings): EffectiveProfile.Proxy {
        val parsed = ProxyUrls.parse(settings.url)
        return parsed.copy(
            username = settings.username?.takeIf { it.isNotEmpty() },
            password = settings.sealedPassword?.let(secrets::decrypt),
        )
    }
}

object ProxyUrls {
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "socks5" to 1080, "socks" to 1080)

    /** Parses and validates a proxy URL. Credentials belong in their own fields, not the URL. */
    fun parse(url: String): EffectiveProfile.Proxy {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        require(uri != null && scheme in DEFAULT_PORTS && !uri.host.isNullOrEmpty()) {
            "proxy URL must look like http://host:port or socks5://host:port"
        }
        require(uri.userInfo == null) { "put proxy credentials in the username and password fields, not in the URL" }
        val type = if (scheme!!.startsWith("socks")) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
        val port = if (uri.port > 0) uri.port else DEFAULT_PORTS.getValue(scheme)
        return EffectiveProfile.Proxy(type, uri.host, port, null, null)
    }
}

object Urls {
    /** Accepts an absolute http(s) URL and returns it trimmed. */
    fun requireHttp(
        url: String,
        what: String,
    ): String {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
            "$what must be an absolute http(s) URL"
        }
        return url.trim()
    }
}
