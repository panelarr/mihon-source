// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

/** What a solver returned: clearance cookies, the user agent they're bound to, and the page. */
data class Solution(
    val url: String,
    val status: Int,
    val userAgent: String?,
    val cookies: List<SolvedCookie>,
    val html: String?,
    val solvedAtMillis: Long,
)

@Serializable
data class SolvedCookie(
    val name: String,
    val value: String,
    val domain: String = "",
    val path: String = "/",
    val expires: Double? = null,
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
)

class SolverException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Talks to FlareSolverr-compatible solvers (FlareSolverr, Byparr). Solves for the same
 * solver and host are coalesced: while one is running, other requests wait for its
 * result instead of starting their own.
 */
class SolverClient(
    private val http: OkHttpClient,
) {
    private val log = LoggerFactory.getLogger(SolverClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Solution>>()

    fun solve(
        solver: SolverSettings,
        url: String,
        host: String,
        proxy: EffectiveProfile.Proxy?,
    ): Solution {
        val key = "${solver.id}|${solver.url}|$host"
        val mine = CompletableFuture<Solution>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return await(running)
        try {
            mine.complete(request(solver, url, proxy))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            mine.completeExceptionally(e)
        } finally {
            inFlight.remove(key, mine)
        }
        return await(mine)
    }

    @Suppress("SwallowedException") // The cause is unwrapped and rethrown.
    private fun await(future: CompletableFuture<Solution>): Solution =
        try {
            future.get()
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw cause as? SolverException ?: SolverException("solver failed: ${cause?.message}", cause)
        }

    private fun request(
        solver: SolverSettings,
        url: String,
        proxy: EffectiveProfile.Proxy?,
    ): Solution {
        val maxTimeout = Duration.ofSeconds(solver.maxTimeoutSeconds ?: DEFAULT_MAX_TIMEOUT_SECONDS)
        val body =
            buildJsonObject {
                put("cmd", "request.get")
                put("url", url)
                put("maxTimeout", maxTimeout.toMillis())
                if (proxy != null) {
                    // The clearance is bound to the client IP, so the solver must use the same proxy.
                    put(
                        "proxy",
                        buildJsonObject {
                            val scheme = if (proxy.type == java.net.Proxy.Type.SOCKS) "socks5" else "http"
                            put("url", "$scheme://${proxy.host}:${proxy.port}")
                            proxy.username?.let { put("username", it) }
                            proxy.password?.let { put("password", it) }
                        },
                    )
                }
            }
        val request =
            Request
                .Builder()
                .url(solver.url.trimEnd('/') + "/v1")
                .post(body.toString().toRequestBody(JSON))
                .build()
        val call =
            http
                .newBuilder()
                .readTimeout(maxTimeout.plusSeconds(EXTRA_WAIT_SECONDS))
                .build()
                .newCall(request)
        log.info("Asking solver '{}' to solve a challenge", solver.id)
        call.execute().use { response ->
            val text = response.body.string()
            val parsed =
                runCatching { json.decodeFromString(Response.serializer(), text) }
                    .getOrElse { throw SolverException("solver '${solver.id}' returned HTTP ${response.code} and no usable JSON") }
            if (parsed.status != "ok" || parsed.solution == null) {
                throw SolverException("solver '${solver.id}' failed: ${parsed.message ?: parsed.status}")
            }
            val s = parsed.solution
            return Solution(
                url = s.url ?: url,
                status = s.status ?: 0,
                userAgent = s.userAgent,
                cookies = s.cookies,
                html = s.response,
                solvedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    @Serializable
    private data class Response(
        val status: String? = null,
        val message: String? = null,
        val solution: SolutionJson? = null,
    )

    @Serializable
    private data class SolutionJson(
        val url: String? = null,
        val status: Int? = null,
        val userAgent: String? = null,
        val cookies: List<SolvedCookie> = emptyList(),
        val response: String? = null,
        val headers: JsonObject? = null,
    )

    private companion object {
        val JSON = "application/json".toMediaType()
        const val DEFAULT_MAX_TIMEOUT_SECONDS = 60L
        const val EXTRA_WAIT_SECONDS = 15L
    }
}
