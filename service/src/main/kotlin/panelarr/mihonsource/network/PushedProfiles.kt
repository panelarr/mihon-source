// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import com.google.protobuf.Duration
import panelarr.mihonsource.secret.SecretBox
import panelarr.source.v1.NetworkProfile
import panelarr.source.v1.RateLimitScope
import panelarr.source.v1.SolverType
import panelarr.source.v1.UserAgent

/** Accepts network profiles pushed by a host (SetNetworkProfile) and stores them per source. */
class PushedProfiles(
    private val store: IntegrationsStore,
    private val secrets: SecretBox,
) {
    fun push(
        sourceId: Long,
        profile: NetworkProfile?,
    ) {
        store.setPushed(sourceId, profile?.let(::convert))
    }

    private fun convert(profile: NetworkProfile): PushedProfile {
        val solver = solver(profile)
        val (mode, agent) = userAgent(profile.userAgent)
        return PushedProfile(
            profile =
                ProfileSettings(
                    id = PUSHED,
                    name = profile.name.ifBlank { PUSHED },
                    proxy = proxy(profile),
                    solverId = solver?.id,
                    rateLimit = rateLimit(profile),
                    concurrency = profile.concurrency,
                    userAgentMode = mode,
                    userAgent = agent,
                    headers = profile.headersMap,
                    sharedCookieJar = profile.sharedCookieJar.ifBlank { null },
                    connectTimeoutMillis =
                        profile.timeouts
                            .takeIf { it.hasConnect() }
                            ?.connect
                            ?.millis(),
                    readTimeoutMillis =
                        profile.timeouts
                            .takeIf { it.hasRead() }
                            ?.read
                            ?.millis(),
                    callTimeoutMillis =
                        profile.timeouts
                            .takeIf { it.hasCall() }
                            ?.call
                            ?.millis(),
                ),
            solver = solver,
        )
    }

    private fun solver(profile: NetworkProfile): SolverSettings? {
        if (!profile.hasSolver() || profile.solver.url.isBlank()) return null
        val solver = profile.solver
        return SolverSettings(
            id = PUSHED,
            name = PUSHED,
            type = if (solver.type == SolverType.SOLVER_TYPE_BYPARR) SolverSettings.Type.BYPARR else SolverSettings.Type.FLARESOLVERR,
            url = Urls.requireHttp(solver.url, "solver URL"),
            sessionTtlSeconds = solver.takeIf { it.hasSessionTtl() }?.sessionTtl?.seconds,
            maxTimeoutSeconds = solver.takeIf { it.hasMaxTimeout() }?.maxTimeout?.seconds,
            asResponseFallback = solver.asResponseFallback,
        )
    }

    private fun proxy(profile: NetworkProfile): ProxySettings? {
        if (!profile.hasProxy() || profile.proxy.url.isBlank()) return null
        ProxyUrls.parse(profile.proxy.url)
        return ProxySettings(
            url = profile.proxy.url.trim(),
            username = profile.proxy.username.ifBlank { null },
            sealedPassword =
                profile.proxy.password
                    .ifEmpty { null }
                    ?.let(secrets::encrypt),
        )
    }

    private fun rateLimit(profile: NetworkProfile): RateLimitSettings? {
        val limit = profile.rateLimit
        if (!profile.hasRateLimit() || limit.requests <= 0 || !limit.hasPeriod()) return null
        return RateLimitSettings(
            requests = limit.requests,
            periodMillis = limit.period.millis(),
            burst = limit.burst,
            perHost = limit.scope == RateLimitScope.RATE_LIMIT_SCOPE_HOST,
        )
    }

    private fun userAgent(agent: UserAgent): Pair<UserAgentMode, String?> =
        when (agent.modeCase) {
            UserAgent.ModeCase.FIXED -> UserAgentMode.FIXED to agent.fixed
            UserAgent.ModeCase.RANDOM_DESKTOP -> UserAgentMode.RANDOM_DESKTOP to null
            UserAgent.ModeCase.FROM_SOLVER -> UserAgentMode.FROM_SOLVER to null
            else -> UserAgentMode.DEFAULT to null
        }

    private fun Duration.millis(): Long = seconds * MILLIS + nanos / NANOS_PER_MILLI

    private companion object {
        const val PUSHED = "pushed"
        const val MILLIS = 1000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
