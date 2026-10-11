// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

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
        val solver =
            if (profile.hasSolver() && profile.solver.url.isNotBlank()) {
                SolverSettings(
                    id = "pushed",
                    name = "pushed",
                    type = if (profile.solver.type == SolverType.SOLVER_TYPE_BYPARR) SolverSettings.Type.BYPARR else SolverSettings.Type.FLARESOLVERR,
                    url = Urls.requireHttp(profile.solver.url, "solver URL"),
                    sessionTtlSeconds = profile.solver.takeIf { it.hasSessionTtl() }?.sessionTtl?.seconds,
                    maxTimeoutSeconds = profile.solver.takeIf { it.hasMaxTimeout() }?.maxTimeout?.seconds,
                    asResponseFallback = profile.solver.asResponseFallback,
                )
            } else {
                null
            }
        val proxy =
            if (profile.hasProxy() && profile.proxy.url.isNotBlank()) {
                ProxyUrls.parse(profile.proxy.url)
                ProxySettings(
                    url = profile.proxy.url.trim(),
                    username = profile.proxy.username.ifBlank { null },
                    sealedPassword = profile.proxy.password.ifEmpty { null }?.let(secrets::encrypt),
                )
            } else {
                null
            }
        val (mode, agent) =
            when (profile.userAgent.modeCase) {
                UserAgent.ModeCase.FIXED -> UserAgentMode.FIXED to profile.userAgent.fixed
                UserAgent.ModeCase.RANDOM_DESKTOP -> UserAgentMode.RANDOM_DESKTOP to null
                UserAgent.ModeCase.FROM_SOLVER -> UserAgentMode.FROM_SOLVER to null
                else -> UserAgentMode.DEFAULT to null
            }
        return PushedProfile(
            profile =
                ProfileSettings(
                    id = "pushed",
                    name = profile.name.ifBlank { "pushed" },
                    proxy = proxy,
                    solverId = solver?.id,
                    rateLimit =
                        if (profile.hasRateLimit() && profile.rateLimit.requests > 0 && profile.rateLimit.hasPeriod()) {
                            RateLimitSettings(
                                requests = profile.rateLimit.requests,
                                periodMillis = profile.rateLimit.period.seconds * MILLIS + profile.rateLimit.period.nanos / NANOS_PER_MILLI,
                                burst = profile.rateLimit.burst,
                                perHost = profile.rateLimit.scope == RateLimitScope.RATE_LIMIT_SCOPE_HOST,
                            )
                        } else {
                            null
                        },
                    concurrency = profile.concurrency,
                    userAgentMode = mode,
                    userAgent = agent,
                    headers = profile.headersMap,
                    sharedCookieJar = profile.sharedCookieJar.ifBlank { null },
                    connectTimeoutMillis = profile.timeouts.takeIf { it.hasConnect() }?.connect?.let { it.seconds * MILLIS + it.nanos / NANOS_PER_MILLI },
                    readTimeoutMillis = profile.timeouts.takeIf { it.hasRead() }?.read?.let { it.seconds * MILLIS + it.nanos / NANOS_PER_MILLI },
                    callTimeoutMillis = profile.timeouts.takeIf { it.hasCall() }?.call?.let { it.seconds * MILLIS + it.nanos / NANOS_PER_MILLI },
                ),
            solver = solver,
        )
    }

    private companion object {
        const val MILLIS = 1000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
