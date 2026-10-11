// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import com.google.protobuf.Duration
import panelarr.mihon.v1.AssignProfileRequest
import panelarr.mihon.v1.AssignProfileResponse
import panelarr.mihon.v1.Assignment
import panelarr.mihon.v1.DeleteProfileRequest
import panelarr.mihon.v1.DeleteProfileResponse
import panelarr.mihon.v1.DeleteSolverRequest
import panelarr.mihon.v1.DeleteSolverResponse
import panelarr.mihon.v1.ListAssignmentsRequest
import panelarr.mihon.v1.ListAssignmentsResponse
import panelarr.mihon.v1.ListProfilesRequest
import panelarr.mihon.v1.ListProfilesResponse
import panelarr.mihon.v1.ListSolversRequest
import panelarr.mihon.v1.ListSolversResponse
import panelarr.mihon.v1.MihonIntegrationsServiceGrpcKt
import panelarr.mihon.v1.NetworkProfile
import panelarr.mihon.v1.ProfileOrigin
import panelarr.mihon.v1.PutProfileRequest
import panelarr.mihon.v1.PutProfileResponse
import panelarr.mihon.v1.PutSolverRequest
import panelarr.mihon.v1.PutSolverResponse
import panelarr.mihon.v1.SetDefaultProfileRequest
import panelarr.mihon.v1.SetDefaultProfileResponse
import panelarr.mihon.v1.Solver
import panelarr.mihon.v1.SolverType
import panelarr.mihon.v1.UserAgentMode
import panelarr.mihon.v1.assignProfileResponse
import panelarr.mihon.v1.assignment
import panelarr.mihon.v1.deleteProfileResponse
import panelarr.mihon.v1.deleteSolverResponse
import panelarr.mihon.v1.listAssignmentsResponse
import panelarr.mihon.v1.listProfilesResponse
import panelarr.mihon.v1.listSolversResponse
import panelarr.mihon.v1.networkProfile
import panelarr.mihon.v1.proxy
import panelarr.mihon.v1.putProfileResponse
import panelarr.mihon.v1.putSolverResponse
import panelarr.mihon.v1.rateLimit
import panelarr.mihon.v1.setDefaultProfileResponse
import panelarr.mihon.v1.solver
import panelarr.mihonsource.network.EffectiveProfile
import panelarr.mihonsource.network.IntegrationsStore
import panelarr.mihonsource.network.ProfileResolver
import panelarr.mihonsource.network.ProfileSettings
import panelarr.mihonsource.network.ProxySettings
import panelarr.mihonsource.network.ProxyUrls
import panelarr.mihonsource.network.RateLimitSettings
import panelarr.mihonsource.network.SolverSettings
import panelarr.mihonsource.network.Urls
import panelarr.mihonsource.secret.SecretBox
import panelarr.mihonsource.network.UserAgentMode as Mode

/** `panelarr.mihon.v1.MihonIntegrationsService`: solvers, network profiles, assignments. */
class IntegrationsApi(
    private val store: IntegrationsStore,
    private val resolver: ProfileResolver,
    private val secrets: SecretBox,
    /** Ids of the sources currently installed. */
    private val sourceIds: () -> List<Long>,
) : MihonIntegrationsServiceGrpcKt.MihonIntegrationsServiceCoroutineImplBase() {
    override suspend fun listSolvers(request: ListSolversRequest): ListSolversResponse =
        rpc { listSolversResponse { solvers += store.state().solvers.sortedBy { it.id }.map { it.toProto() } } }

    override suspend fun putSolver(request: PutSolverRequest): PutSolverResponse =
        rpc { putSolverResponse { solver = store.putSolver(request.solver.toSettings()).toProto() } }

    override suspend fun deleteSolver(request: DeleteSolverRequest): DeleteSolverResponse =
        rpc {
            store.deleteSolver(request.solverId)
            deleteSolverResponse {}
        }

    override suspend fun listProfiles(request: ListProfilesRequest): ListProfilesResponse =
        rpc {
            val state = store.state()
            listProfilesResponse {
                profiles += state.profiles.sortedBy { it.id }.map { it.toProto() }
                state.defaultProfileId?.let { defaultProfileId = it }
            }
        }

    override suspend fun putProfile(request: PutProfileRequest): PutProfileResponse =
        rpc {
            val existing = store.state().profiles.firstOrNull { it.id == request.profile.id }
            putProfileResponse { profile = store.putProfile(request.profile.toSettings(existing)).toProto() }
        }

    override suspend fun deleteProfile(request: DeleteProfileRequest): DeleteProfileResponse =
        rpc {
            store.deleteProfile(request.profileId)
            deleteProfileResponse {}
        }

    override suspend fun listAssignments(request: ListAssignmentsRequest): ListAssignmentsResponse =
        rpc {
            val state = store.state()
            listAssignmentsResponse {
                assignments += sourceIds().sorted().map(::assignmentOf)
                state.defaultProfileId?.let { defaultProfileId = it }
            }
        }

    override suspend fun assignProfile(request: AssignProfileRequest): AssignProfileResponse =
        rpc {
            val sourceId = SourceIds.parse(request.sourceId)
            store.assign(sourceId, request.profileId.ifBlank { null })
            assignProfileResponse { assignment = assignmentOf(sourceId) }
        }

    override suspend fun setDefaultProfile(request: SetDefaultProfileRequest): SetDefaultProfileResponse =
        rpc {
            store.setDefaultProfile(request.profileId.ifBlank { null })
            setDefaultProfileResponse {}
        }

    private fun assignmentOf(sourceId: Long): Assignment {
        val effective = resolver.resolve(sourceId)
        val pushed = store.pushed(sourceId)
        return assignment {
            this.sourceId = sourceId.toString()
            store.state().assignments[sourceId.toString()]?.let { profileId = it }
            pushed?.let { pushedProfileName = it.profile.name }
            this.effective =
                when (effective.origin) {
                    EffectiveProfile.Origin.NONE -> ProfileOrigin.PROFILE_ORIGIN_NONE
                    EffectiveProfile.Origin.DEFAULT -> ProfileOrigin.PROFILE_ORIGIN_DEFAULT
                    EffectiveProfile.Origin.ASSIGNED -> ProfileOrigin.PROFILE_ORIGIN_ASSIGNED
                    EffectiveProfile.Origin.PUSHED -> ProfileOrigin.PROFILE_ORIGIN_PUSHED
                }
            if (effective.origin == EffectiveProfile.Origin.ASSIGNED || effective.origin == EffectiveProfile.Origin.DEFAULT) {
                effective.profileId?.let { effectiveProfileId = it }
            }
        }
    }

    private fun Solver.toSettings(): SolverSettings {
        require(name.isNotBlank()) { "solver name is required" }
        return SolverSettings(
            id = id,
            name = name.trim(),
            type =
                when (type) {
                    SolverType.SOLVER_TYPE_BYPARR -> SolverSettings.Type.BYPARR
                    SolverType.SOLVER_TYPE_FLARESOLVERR, SolverType.SOLVER_TYPE_UNSPECIFIED, SolverType.UNRECOGNIZED, null ->
                        SolverSettings.Type.FLARESOLVERR
                },
            url = Urls.requireHttp(url, "solver URL"),
            sessionTtlSeconds = if (hasSessionTtl()) sessionTtl.seconds else null,
            maxTimeoutSeconds = if (hasMaxTimeout()) maxTimeout.seconds else null,
            asResponseFallback = asResponseFallback,
        )
    }

    private fun NetworkProfile.toSettings(existing: ProfileSettings?): ProfileSettings {
        require(name.isNotBlank()) { "profile name is required" }
        val proxySettings =
            if (hasProxy() && proxy.url.isNotBlank()) {
                ProxyUrls.parse(proxy.url)
                // An empty password keeps the stored one; clearing the username drops it.
                val keepPassword = proxy.password.isEmpty() && proxy.username.isNotBlank() && existing?.proxy?.sealedPassword != null
                ProxySettings(
                    url = proxy.url.trim(),
                    username = proxy.username.ifBlank { null },
                    sealedPassword =
                        when {
                            proxy.password.isNotEmpty() -> secrets.encrypt(proxy.password)
                            keepPassword -> existing?.proxy?.sealedPassword
                            else -> null
                        },
                )
            } else {
                null
            }
        return ProfileSettings(
            id = id,
            name = name.trim(),
            proxy = proxySettings,
            solverId = solverId.ifBlank { null },
            rateLimit =
                if (hasRateLimit() && rateLimit.requests > 0) {
                    require(rateLimit.hasPeriod() && rateLimit.period.millis() > 0) { "rate limit period is required" }
                    RateLimitSettings(rateLimit.requests, rateLimit.period.millis(), perHost = rateLimit.perHost)
                } else {
                    null
                },
            concurrency = concurrency,
            userAgentMode =
                when (userAgentMode) {
                    UserAgentMode.USER_AGENT_MODE_FIXED -> Mode.FIXED
                    UserAgentMode.USER_AGENT_MODE_RANDOM_DESKTOP -> Mode.RANDOM_DESKTOP
                    UserAgentMode.USER_AGENT_MODE_FROM_SOLVER -> Mode.FROM_SOLVER
                    else -> Mode.DEFAULT
                },
            userAgent = userAgent.ifBlank { null },
            headers = headersMap,
            sharedCookieJar = sharedCookieJar.ifBlank { null },
            connectTimeoutMillis = if (hasConnectTimeout()) connectTimeout.millis() else null,
            readTimeoutMillis = if (hasReadTimeout()) readTimeout.millis() else null,
            callTimeoutMillis = if (hasCallTimeout()) callTimeout.millis() else null,
        )
    }
}

internal fun SolverSettings.toProto(): Solver {
    val s = this
    return solver {
        id = s.id
        name = s.name
        type =
            when (s.type) {
                SolverSettings.Type.FLARESOLVERR -> SolverType.SOLVER_TYPE_FLARESOLVERR
                SolverSettings.Type.BYPARR -> SolverType.SOLVER_TYPE_BYPARR
            }
        url = s.url
        s.sessionTtlSeconds?.let { sessionTtl = seconds(it) }
        s.maxTimeoutSeconds?.let { maxTimeout = seconds(it) }
        asResponseFallback = s.asResponseFallback
    }
}

/** A profile as the API shows it: the proxy password is never included. */
internal fun ProfileSettings.toProto(): NetworkProfile {
    val p = this
    return networkProfile {
        id = p.id
        name = p.name
        p.proxy?.let { settings ->
            proxy =
                proxy {
                    url = settings.url
                    settings.username?.let { username = it }
                    hasPassword = settings.sealedPassword != null
                }
        }
        p.solverId?.let { solverId = it }
        p.rateLimit?.let { limit ->
            rateLimit =
                rateLimit {
                    requests = limit.requests
                    period = millis(limit.periodMillis)
                    perHost = limit.perHost
                }
        }
        concurrency = p.concurrency
        userAgentMode =
            when (p.userAgentMode) {
                Mode.DEFAULT -> UserAgentMode.USER_AGENT_MODE_UNSPECIFIED
                Mode.FIXED -> UserAgentMode.USER_AGENT_MODE_FIXED
                Mode.RANDOM_DESKTOP -> UserAgentMode.USER_AGENT_MODE_RANDOM_DESKTOP
                Mode.FROM_SOLVER -> UserAgentMode.USER_AGENT_MODE_FROM_SOLVER
            }
        p.userAgent?.let { userAgent = it }
        headers.putAll(p.headers)
        p.sharedCookieJar?.let { sharedCookieJar = it }
        p.connectTimeoutMillis?.let { connectTimeout = millis(it) }
        p.readTimeoutMillis?.let { readTimeout = millis(it) }
        p.callTimeoutMillis?.let { callTimeout = millis(it) }
    }
}

internal fun Duration.millis(): Long = seconds * MILLIS_PER_SECOND + nanos / NANOS_PER_MILLI

internal fun millis(value: Long): Duration =
    Duration
        .newBuilder()
        .setSeconds(value / MILLIS_PER_SECOND)
        .setNanos(((value % MILLIS_PER_SECOND) * NANOS_PER_MILLI).toInt())
        .build()

internal fun seconds(value: Long): Duration = Duration.newBuilder().setSeconds(value).build()

private const val MILLIS_PER_SECOND = 1000L
private const val NANOS_PER_MILLI = 1_000_000L

/** Source ids travel as strings in the API. */
object SourceIds {
    fun parse(value: String): Long = value.trim().toLongOrNull() ?: throw IllegalArgumentException("invalid source id '$value'")
}
