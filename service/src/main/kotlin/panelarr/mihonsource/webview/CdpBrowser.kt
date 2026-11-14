// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import panelarr.mihonsource.config.WebViewConfig
import panelarr.mihonsource.metrics.Metrics
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The connection to the optional browser service: a headless Chromium spoken to over the
 * Chrome DevTools Protocol. It connects on first use, reconnects on the next call after
 * the browser went away (until [close]), keeps at most [WebViewConfig.maxPages] pages open at once, and
 * closes browser contexts nobody used for [WebViewConfig.idleTimeout].
 *
 * The DevTools port has no authentication: anyone who reaches it controls the browser. It
 * belongs on an internal network only.
 */
class CdpBrowser(
    private val config: WebViewConfig,
    http: OkHttpClient? = null,
    private val metrics: Metrics? = null,
    private val now: () -> Instant = Instant::now,
) : AutoCloseable {
    /** The result of the last [check]. */
    data class Status(
        val up: Boolean,
        /** The browser's product, e.g. `Chrome/140.0.0.0`, when up. */
        val browser: String? = null,
        /** Why it's down, when it isn't up. */
        val error: String? = null,
        val checkedAt: Instant,
    )

    private class Context(
        val id: String,
        val connection: CdpConnection,
    ) {
        @Volatile var lastUsed: Instant = Instant.EPOCH
        val users = AtomicInteger()
    }

    private val log = LoggerFactory.getLogger(CdpBrowser::class.java)

    /** Without a client given, one is made here, and shut down again by [close]. */
    private val ownsClient = http == null
    private val http: OkHttpClient = http ?: defaultClient()
    private val scheme = config.url.substringBefore(':').lowercase()

    /** The configured URL, as http(s) (OkHttp's URLs only know those). */
    private val target = config.url.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http").toHttpUrl()
    private val connectLock = Mutex()
    private val checkLock = Mutex()
    private val contextLock = Mutex()
    private val pages = Semaphore(config.maxPages)
    private val openPages = AtomicInteger()
    private val contexts = ConcurrentHashMap<String, Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var current: CdpConnection? = null

    @Volatile private var closing = false

    @Volatile private var status: Status? = null

    @Volatile private var lastCheck: Status? = null

    /** Where the browser is, for logs: host and port only, never credentials or paths. */
    val address: String = "${target.host}:${target.port}"

    init {
        metrics?.webViewGauges(up = { if (status?.up == true) 1 else 0 }, pagesOpen = openPages::get)
        scope.launch {
            val interval = (config.idleTimeout.dividedBy(REAPS_PER_TIMEOUT)).coerceIn(MIN_REAP, MAX_REAP)
            while (isActive) {
                delay(interval.toMillis())
                reapIdleContexts()
            }
        }
    }

    /** Pages open right now. */
    fun openPages(): Int = openPages.get()

    /** The last known status, without checking. Null until the first check or use. */
    fun lastStatus(): Status? = status

    /**
     * Checks that the browser answers, connecting if needed, within [timeout]. Results are
     * reused for a few seconds, so frequent health probes don't hammer the browser.
     */
    suspend fun check(timeout: Duration = CHECK_TIMEOUT): Status =
        checkLock.withLock {
            // Only a check's own result is reused: connecting or a drop updates [status] too, without a product.
            lastCheck?.takeIf { it === status && Duration.between(it.checkedAt, now()) < STATUS_TTL }?.let { return it }
            val result =
                try {
                    withTimeoutOrNull(timeout.toMillis()) {
                        val version = connection().send("Browser.getVersion", timeout = timeout)
                        Status(up = true, browser = version.string("product"), checkedAt = now())
                    } ?: Status(up = false, error = "the browser didn't answer within ${timeout.toSeconds()}s", checkedAt = now())
                } catch (e: WebViewException) {
                    Status(up = false, error = e.message, checkedAt = now())
                }
            status = result
            lastCheck = result
            result
        }

    /**
     * The open connection, connecting first when there's none (or the last one dropped).
     * Fails with [WebViewUnavailableException] when the browser can't be reached.
     */
    suspend fun connection(): CdpConnection {
        current?.takeIf { it.isOpen }?.let { return it }
        return connectLock.withLock {
            if (closing) throw closedError()
            current?.takeIf { it.isOpen } ?: connect().also { fresh ->
                // [close] ran while connecting: don't leave the new connection behind.
                if (closing) {
                    fresh.close()
                    throw closedError()
                }
                current = fresh
            }
        }
    }

    private fun closedError() = WebViewUnavailableException("the browser connection was shut down")

    /**
     * Opens a page, runs [block] with it and closes it. With a [contextKey] the page opens
     * in that key's own browser context (separate cookies and storage), created on first
     * use with [proxyServer]. Waits up to the load timeout for a free page.
     */
    suspend fun <T> withPage(
        contextKey: String? = null,
        proxyServer: String? = null,
        block: suspend (CdpPage) -> T,
    ): T {
        // Decided by a flag rather than the timeout's result: the timeout can fire right after the
        // permit was taken, and that permit must still be released.
        var acquired = false
        withTimeoutOrNull(config.loadTimeout.toMillis()) {
            pages.acquire()
            acquired = true
        }
        if (!acquired) {
            metrics?.webViewFailure("busy")
            throw CdpTimeoutException("all ${config.maxPages} browser pages stayed busy for ${config.loadTimeout.toSeconds()}s")
        }
        try {
            val existing = current?.takeIf { it.isOpen }
            val (context, page) =
                try {
                    open(existing ?: connection(), contextKey, proxyServer)
                } catch (
                    @Suppress("SwallowedException") e: WebViewUnavailableException,
                ) {
                    // The browser may have gone away before the drop was noticed: retry once, on a new
                    // connection. A connect that just failed isn't tried again.
                    if (existing == null) throw e
                    open(connection(), contextKey, proxyServer)
                }
            openPages.incrementAndGet()
            try {
                return block(page)
            } finally {
                openPages.decrementAndGet()
                withContext(NonCancellable) { page.close() }
                context?.let(::release)
            }
        } catch (e: WebViewException) {
            count(e)
            throw e
        } finally {
            pages.release()
        }
    }

    /** Opens a page, in [contextKey]'s context when there's a key. */
    private suspend fun open(
        connection: CdpConnection,
        contextKey: String?,
        proxyServer: String?,
    ): Pair<Context?, CdpPage> {
        val context = contextKey?.let { acquireContext(connection, it, proxyServer) }
        try {
            return context to openPage(connection, context?.id)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            context?.let(::release)
            throw e
        }
    }

    private fun release(context: Context) {
        context.lastUsed = now()
        context.users.decrementAndGet()
    }

    /** Closes [contextKey]'s browser context now, if it's open and unused. */
    suspend fun closeContext(contextKey: String) {
        contextLock.withLock {
            val context = contexts[contextKey] ?: return
            if (context.users.get() > 0) return
            contexts.remove(contextKey)
            dispose(context)
        }
    }

    /** Browser contexts open right now. */
    fun openContexts(): Int = contexts.size

    /** Closes the connection; later calls fail with [WebViewUnavailableException] instead of reconnecting. */
    override fun close() {
        closing = true
        scope.cancel()
        current?.close()
        current = null
        contexts.clear()
        if (ownsClient) {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private suspend fun connect(): CdpConnection {
        try {
            val connection = CdpConnection.open(http, webSocketRequest(), CONNECT_TIMEOUT)
            // Contexts lived on the old connection and were disposed with it.
            contexts.clear()
            connection.closed.invokeOnCompletion {
                if (!closing && current === connection) {
                    log.warn("Lost the connection to the browser service at {}", address)
                    metrics?.webViewFailure("disconnect")
                    status = Status(up = false, error = "the connection to the browser dropped", checkedAt = now())
                }
            }
            if (status?.up != true) log.info("Connected to the browser service at {}", address)
            status = Status(up = true, browser = status?.browser, checkedAt = now())
            return connection
        } catch (e: WebViewUnavailableException) {
            metrics?.webViewFailure("connect")
            if (status?.up != false) log.warn("Can't reach the browser service at {}: {}", address, e.message)
            status = Status(up = false, error = e.message, checkedAt = now())
            throw e
        }
    }

    /**
     * The WebSocket request: a ws(s) URL is used as it is; for http(s) the browser's
     * endpoint is read from `/json/version`.
     */
    private suspend fun webSocketRequest(): Request {
        val url =
            when (scheme) {
                "http", "https" -> discover()
                else -> config.url
            }
        return Request
            .Builder()
            .url(url)
            .apply { hostHeader()?.let { header("Host", it) } }
            .build()
    }

    /** Reads the browser's WebSocket path from `/json/version` and points it at [target]. */
    private suspend fun discover(): String =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(
                        target
                            .newBuilder()
                            .encodedPath("/json/version")
                            .query(null)
                            .build(),
                    ).apply { hostHeader()?.let { header("Host", it) } }
                    .build()
            val body =
                try {
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw WebViewUnavailableException("the browser answered HTTP ${response.code} on /json/version")
                        }
                        response.body.string()
                    }
                } catch (e: IOException) {
                    if (e is WebViewUnavailableException) throw e
                    throw WebViewUnavailableException("can't reach the browser: ${e.message ?: e.javaClass.simpleName}", e)
                }
            val advertised =
                runCatching { Json.parseToJsonElement(body).jsonObject.string("webSocketDebuggerUrl") }.getOrNull()
                    ?: throw WebViewUnavailableException("the browser's /json/version has no webSocketDebuggerUrl")
            // The browser names itself as it sees itself (often localhost); reach it where it's configured instead.
            val path =
                runCatching {
                    advertised
                        .replaceFirst(Regex("^ws"), "http")
                        .toHttpUrl()
                        .encodedPath
                }.getOrElse { throw WebViewUnavailableException("the browser advertised an invalid WebSocket URL") }
            val scheme = if (target.isHttps) "wss" else "ws"
            "$scheme://${target.host.let { if (':' in it) "[$it]" else it }}:${target.port}$path"
        }

    /**
     * Chromium refuses DevTools requests whose Host header is a name rather than an IP
     * address or `localhost` (a DNS-rebinding guard). Over plain http/ws to a named host,
     * such as a compose service name, say `localhost` instead. Over TLS the real name is
     * kept, as a proxy in front needs it.
     */
    private fun hostHeader(): String? {
        val host = target.host
        val plain = scheme == "http" || scheme == "ws"
        val isIp = ':' in host || IPV4.matches(host)
        return if (plain && !isIp && host != "localhost") "localhost:${target.port}" else null
    }

    private suspend fun acquireContext(
        connection: CdpConnection,
        key: String,
        proxyServer: String?,
    ): Context =
        contextLock.withLock {
            val existing = contexts[key]?.takeIf { it.connection === connection && connection.isOpen }
            val context =
                existing ?: run {
                    val params =
                        buildJsonObject {
                            // The browser disposes the context itself if this connection drops.
                            put("disposeOnDetach", JsonPrimitive(true))
                            proxyServer?.let { put("proxyServer", JsonPrimitive(it)) }
                        }
                    val id = connection.send("Target.createBrowserContext", params).required("browserContextId")
                    Context(id, connection).also { contexts[key] = it }
                }
            context.users.incrementAndGet()
            context.lastUsed = now()
            context
        }

    private suspend fun openPage(
        connection: CdpConnection,
        contextId: String?,
    ): CdpPage {
        val created =
            connection.send(
                "Target.createTarget",
                buildJsonObject {
                    put("url", JsonPrimitive("about:blank"))
                    contextId?.let { put("browserContextId", JsonPrimitive(it)) }
                },
            )
        val targetId = created.required("targetId")
        var ready = false
        try {
            val attached =
                connection.send(
                    "Target.attachToTarget",
                    buildJsonObject {
                        put("targetId", JsonPrimitive(targetId))
                        put("flatten", JsonPrimitive(true))
                    },
                )
            val page = CdpPage(connection, targetId, attached.required("sessionId"), config.loadTimeout) { metrics?.webViewLoad(it) }
            page.send("Page.enable")
            ready = true
            return page
        } finally {
            // Any failure, cancellation included: the tab exists in the browser and nothing else would close it.
            if (!ready) {
                withContext(NonCancellable) {
                    runCatching {
                        connection.send(
                            "Target.closeTarget",
                            buildJsonObject { put("targetId", JsonPrimitive(targetId)) },
                            timeout = CLOSE_TIMEOUT,
                        )
                    }
                }
            }
        }
    }

    private suspend fun reapIdleContexts() {
        val cutoff = now().minus(config.idleTimeout)
        contexts.keys.toList().forEach { key ->
            contextLock.withLock {
                val context = contexts[key] ?: return@withLock
                if (context.users.get() == 0 && context.lastUsed.isBefore(cutoff)) {
                    contexts.remove(key)
                    dispose(context)
                }
            }
        }
    }

    private suspend fun dispose(context: Context) {
        if (!context.connection.isOpen) return
        runCatching {
            context.connection.send(
                "Target.disposeBrowserContext",
                buildJsonObject { put("browserContextId", JsonPrimitive(context.id)) },
            )
        }
    }

    private fun count(e: WebViewException) {
        val kind =
            when (e) {
                is CdpTimeoutException -> "timeout"
                is CdpLoadException -> "load"
                is CdpProtocolException -> "protocol"
                else -> return // Connect failures and drops are counted where they happen.
            }
        metrics?.webViewFailure(kind)
    }

    companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val CHECK_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val CLOSE_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val STATUS_TTL: Duration = Duration.ofSeconds(10)
        private const val REAPS_PER_TIMEOUT = 4L
        private val MIN_REAP: Duration = Duration.ofSeconds(1)
        private val MAX_REAP: Duration = Duration.ofSeconds(30)
        private const val PING_SECONDS = 20L
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .readTimeout(CONNECT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                // Notices a browser that vanished without closing the socket.
                .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
