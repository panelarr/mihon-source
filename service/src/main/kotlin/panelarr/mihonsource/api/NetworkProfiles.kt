// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import com.google.protobuf.Duration
import panelarr.mihonsource.network.EffectiveProfile
import panelarr.mihonsource.network.RateLimitSettings
import panelarr.mihonsource.network.SolverSettings
import panelarr.mihonsource.network.UserAgentMode
import panelarr.source.v1.GetNetworkProfileResponse
import panelarr.source.v1.NetworkProfile
import panelarr.source.v1.NetworkProfileOrigin
import panelarr.source.v1.RateLimitScope
import panelarr.source.v1.SolverType
import panelarr.source.v1.getNetworkProfileResponse
import panelarr.source.v1.networkProfile
import panelarr.source.v1.proxy
import panelarr.source.v1.rateLimit
import panelarr.source.v1.solver
import panelarr.source.v1.timeouts
import panelarr.source.v1.userAgent

/**
 * The profile a source uses, for GetNetworkProfile: pushed by a host, the service's own
 * assignment or default, or none. The proxy password is never returned.
 */
internal fun EffectiveProfile.toResponse(): GetNetworkProfileResponse {
    val p = this
    return getNetworkProfileResponse {
        origin =
            when (p.origin) {
                EffectiveProfile.Origin.PUSHED -> NetworkProfileOrigin.NETWORK_PROFILE_ORIGIN_PUSHED
                EffectiveProfile.Origin.ASSIGNED, EffectiveProfile.Origin.DEFAULT -> NetworkProfileOrigin.NETWORK_PROFILE_ORIGIN_PLUGIN
                EffectiveProfile.Origin.NONE -> NetworkProfileOrigin.NETWORK_PROFILE_ORIGIN_NONE
            }
        if (p.origin != EffectiveProfile.Origin.NONE) profile = p.toProto()
    }
}

private fun EffectiveProfile.toProto(): NetworkProfile {
    val p = this
    return networkProfile {
        p.profileId?.let { name = it }
        p.proxy?.let { proxy = it.toSourceProxy() }
        p.solver?.let { solver = it.toSourceSolver() }
        p.rateLimit?.let { rateLimit = it.toSourceRateLimit() }
        concurrency = p.concurrency
        p.toSourceUserAgent()?.let { userAgent = it }
        headers.putAll(p.headers)
        p.sharedCookieJar?.let { sharedCookieJar = it }
        p.toSourceTimeouts()?.let { timeouts = it }
    }
}

private fun EffectiveProfile.Proxy.toSourceProxy() =
    proxy {
        val scheme = if (type == java.net.Proxy.Type.SOCKS) "socks5" else "http"
        url = "$scheme://$host:$port"
        this@toSourceProxy.username?.let { username = it }
    }

private fun RateLimitSettings.toSourceRateLimit() =
    rateLimit {
        requests = this@toSourceRateLimit.requests
        period = profileDuration(periodMillis)
        burst = this@toSourceRateLimit.burst
        scope = if (perHost) RateLimitScope.RATE_LIMIT_SCOPE_HOST else RateLimitScope.RATE_LIMIT_SCOPE_SOURCE
    }

private fun EffectiveProfile.toSourceUserAgent() =
    when (userAgentMode) {
        UserAgentMode.FIXED -> userAgent { fixed = this@toSourceUserAgent.userAgent.orEmpty() }
        UserAgentMode.RANDOM_DESKTOP -> userAgent { randomDesktop = true }
        UserAgentMode.FROM_SOLVER -> userAgent { fromSolver = true }
        UserAgentMode.DEFAULT -> null
    }

private fun EffectiveProfile.toSourceTimeouts() =
    if (connectTimeout == null && readTimeout == null && callTimeout == null) {
        null
    } else {
        timeouts {
            connectTimeout?.let { connect = profileDuration(it.toMillis()) }
            readTimeout?.let { read = profileDuration(it.toMillis()) }
            callTimeout?.let { call = profileDuration(it.toMillis()) }
        }
    }

private fun SolverSettings.toSourceSolver() =
    solver {
        type =
            if (this@toSourceSolver.type ==
                SolverSettings.Type.BYPARR
            ) {
                SolverType.SOLVER_TYPE_BYPARR
            } else {
                SolverType.SOLVER_TYPE_FLARESOLVERR
            }
        url = this@toSourceSolver.url
        sessionTtlSeconds?.let { sessionTtl = Duration.newBuilder().setSeconds(it).build() }
        maxTimeoutSeconds?.let { maxTimeout = Duration.newBuilder().setSeconds(it).build() }
        asResponseFallback = this@toSourceSolver.asResponseFallback
    }

private fun profileDuration(ms: Long): Duration {
    val d = java.time.Duration.ofMillis(ms)
    return Duration
        .newBuilder()
        .setSeconds(d.seconds)
        .setNanos(d.nano)
        .build()
}
