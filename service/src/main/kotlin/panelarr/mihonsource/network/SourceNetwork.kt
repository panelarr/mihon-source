// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.network

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The networking of one source: an [OkHttpClient] whose proxy, cookies, user agent, rate
 * limit, concurrency, timeouts and challenge solver all follow that source's current
 * network profile.
 *
 * The client is created once, possibly before the source's id is known (extensions build
 * their clients in constructors). Everything profile-dependent is therefore looked up
 * when a request or connection is made, through [sourceId]. The client also has its own
 * connection pool, so a connection opened through one source's proxy is never reused by
 * another source.
 */
class SourceNetwork(
    private val sourceId: () -> Long?,
    private val networks: SourceNetworks,
) {
    private val pool = ConnectionPool()
    private val solverAgents = ConcurrentHashMap<String, String>()
    private var cachedVersion = -1L

    @Volatile
    private var cached: EffectiveProfile = EffectiveProfile.DIRECT
    private var sourceLimiter: RateLimiter? = null
    private val hostLimiters = ConcurrentHashMap<String, RateLimiter>()
    private var concurrency: Pair<Int, Semaphore>? = null

    /** The profile in effect now. */
    fun profile(): EffectiveProfile {
        val version = networks.version.get()
        if (version != cachedVersion) {
            synchronized(this) {
                if (version != cachedVersion) {
                    val previous = cached
                    cached = sourceId()?.let(networks::resolve) ?: networks.defaultProfile()
                    cachedVersion = version
                    if (previous.proxy != cached.proxy) pool.evictAll()
                }
            }
        }
        return cached
    }

    /** The user agent this source sends unless an extension sets its own. */
    fun userAgent(): String {
        val profile = profile()
        return when (profile.userAgentMode) {
            UserAgentMode.FIXED -> profile.userAgent?.takeIf { it.isNotBlank() } ?: UserAgents.DEFAULT
            UserAgentMode.RANDOM_DESKTOP -> UserAgents.desktopFor(sourceId() ?: 0)
            UserAgentMode.FROM_SOLVER, UserAgentMode.DEFAULT -> UserAgents.DEFAULT
        }
    }

    val cookieJar: CookieJar =
        object : CookieJar {
            override fun saveFromResponse(
                url: HttpUrl,
                cookies: List<Cookie>,
            ) = jar().saveFromResponse(url, cookies)

            override fun loadForRequest(url: HttpUrl): List<Cookie> = jar().loadForRequest(url)
        }

    /** The cookie jar in use now (the source's own, or a shared one). */
    fun jar(): FileCookieJar = networks.cookieJar(sourceId(), profile().sharedCookieJar)

    val client: OkHttpClient by lazy {
        networks.base
            .newBuilder()
            .connectionPool(pool)
            .proxySelector(ProfileProxySelector())
            .proxyAuthenticator(ProxyAuth())
            .cookieJar(cookieJar)
            .addInterceptor(::timeouts)
            .addInterceptor(::headers)
            .addInterceptor(::limits)
            .addInterceptor(::solver)
            .build()
    }

    private fun timeouts(chain: Interceptor.Chain): Response {
        val profile = profile()
        var next = chain
        profile.connectTimeout?.let { next = next.withConnectTimeout(it.toMillis().toInt(), TimeUnit.MILLISECONDS) }
        profile.readTimeout?.let {
            next = next.withReadTimeout(it.toMillis().toInt(), TimeUnit.MILLISECONDS).withWriteTimeout(it.toMillis().toInt(), TimeUnit.MILLISECONDS)
        }
        profile.callTimeout?.let { chain.call().timeout().timeout(it.toMillis(), TimeUnit.MILLISECONDS) }
        return next.proceed(chain.request())
    }

    private fun headers(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val profile = profile()
        val builder = request.newBuilder()
        val current = request.header(USER_AGENT)
        // Only replace user agents we handed out ourselves; an extension's own choice wins.
        if (current == null || UserAgents.isOurs(current)) {
            val solverAgent = solverAgents[request.url.host]
            val agent = if (profile.userAgentMode == UserAgentMode.FROM_SOLVER && solverAgent != null) solverAgent else userAgent()
            builder.header(USER_AGENT, agent)
        }
        profile.headers.forEach { (name, value) -> if (request.header(name) == null) builder.header(name, value) }
        return chain.proceed(builder.build())
    }

    private fun limits(chain: Interceptor.Chain): Response {
        val profile = profile()
        val semaphore = semaphoreFor(profile.concurrency)
        semaphore?.acquire()
        try {
            profile.rateLimit?.let { limit ->
                limiterFor(limit, chain.request().url.host).acquire()
            }
            return chain.proceed(chain.request())
        } finally {
            semaphore?.release()
        }
    }

    @Synchronized
    private fun semaphoreFor(limit: Int): Semaphore? {
        if (limit <= 0) return null
        val current = concurrency
        if (current?.first == limit) return current.second
        return Semaphore(limit, true).also { concurrency = limit to it }
    }

    @Synchronized
    private fun limiterFor(
        settings: RateLimitSettings,
        host: String,
    ): RateLimiter {
        if (settings.perHost) {
            val existing = hostLimiters[host]
            if (existing != null && existing.matches(settings)) return existing
            return RateLimiter.of(settings).also { hostLimiters[host] = it }
        }
        val existing = sourceLimiter
        if (existing != null && existing.matches(settings)) return existing
        return RateLimiter.of(settings).also { sourceLimiter = it }
    }

    private fun solver(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val profile = profile()
        val solver = profile.solver ?: return response
        if (!Challenges.isChallenge(response)) return response
        response.close()

        val host = request.url.host
        val solution =
            try {
                networks.solvers.solve(solver, request.url.toString(), host, profile.proxy)
            } catch (e: SolverException) {
                throw ChallengeException("challenge on $host not solved: ${e.message}", e)
            }
        solution.userAgent?.let { solverAgents[host] = it }
        jar().saveFromResponse(request.url, Challenges.toCookies(solution, request.url))

        val retry =
            request
                .newBuilder()
                .apply { solution.userAgent?.let { header(USER_AGENT, it) } }
                .build()
        val retried = chain.proceed(retry)
        if (!Challenges.isChallenge(retried) || !solver.asResponseFallback || solution.html == null) return retried
        retried.close()
        return Response
            .Builder()
            .request(retry)
            .protocol(Protocol.HTTP_1_1)
            .code(if (solution.status in SUCCESS) solution.status else HTTP_OK)
            .message("OK (from solver)")
            .body(solution.html.toResponseBody(HTML))
            .build()
    }

    private inner class ProfileProxySelector : ProxySelector() {
        override fun select(uri: URI?): List<java.net.Proxy> {
            val proxy = profile().proxy ?: return listOf(java.net.Proxy.NO_PROXY)
            return listOf(java.net.Proxy(proxy.type, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
        }

        override fun connectFailed(
            uri: URI?,
            sa: SocketAddress?,
            ioe: IOException?,
        ) = Unit
    }

    private inner class ProxyAuth : Authenticator {
        override fun authenticate(
            route: okhttp3.Route?,
            response: Response,
        ): Request? {
            val proxy = profile().proxy ?: return null
            val username = proxy.username ?: return null
            if (response.request.header(PROXY_AUTHORIZATION) != null) return null // Credentials were rejected.
            return response.request
                .newBuilder()
                .header(PROXY_AUTHORIZATION, Credentials.basic(username, proxy.password.orEmpty()))
                .build()
        }
    }

    private companion object {
        const val USER_AGENT = "User-Agent"
        const val PROXY_AUTHORIZATION = "Proxy-Authorization"
        const val HTTP_OK = 200
        val SUCCESS = 200..299
        val HTML = okhttp3.MediaType.Companion.run { "text/html; charset=utf-8".toMediaType() }
    }
}

/** A request was blocked by an anti-bot challenge that couldn't be passed. */
class ChallengeException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Creates [SourceNetwork]s and holds what they share: the base client (dispatcher and
 * defaults), cookie jars, the solver client, and the profile resolver.
 */
class SourceNetworks(
    private val resolver: ProfileResolver,
    private val cookieDir: java.nio.file.Path,
    val solvers: SolverClient,
    val base: OkHttpClient = defaultBase(),
) {
    /** Bumped on every configuration change, so networks re-resolve their profile. */
    val version = AtomicLong()
    private val jars = ConcurrentHashMap<String, FileCookieJar>()

    fun configurationChanged() {
        version.incrementAndGet()
    }

    fun create(sourceId: () -> Long?): SourceNetwork = SourceNetwork(sourceId, this)

    fun resolve(sourceId: Long): EffectiveProfile = resolver.resolve(sourceId)

    /** Profile for clients whose source isn't known (global helpers). */
    fun defaultProfile(): EffectiveProfile = EffectiveProfile.DIRECT

    fun cookieJar(
        sourceId: Long?,
        shared: String?,
    ): FileCookieJar {
        val name =
            when {
                shared != null -> "shared-${shared.filter { it.isLetterOrDigit() || it == '-' }}"
                sourceId != null -> sourceId.toString()
                else -> "global"
            }
        return jars.computeIfAbsent(name) { FileCookieJar(cookieDir.resolve("$it.json")) }
    }

    /** Deletes a source's own cookie jar. */
    fun deleteCookies(sourceId: Long) {
        jars.remove(sourceId.toString())
        java.nio.file.Files.deleteIfExists(cookieDir.resolve("$sourceId.json"))
    }

    companion object {
        fun defaultBase(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(2, TimeUnit.MINUTES)
                .dispatcher(
                    okhttp3.Dispatcher().apply {
                        maxRequests = 256
                        maxRequestsPerHost = 16
                    },
                ).build()
    }
}
