// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** An event the browser sent: [sessionId] is null for browser-level events. */
data class CdpEvent(
    val method: String,
    val params: JsonObject,
    val sessionId: String?,
)

/**
 * One WebSocket to the browser, in the DevTools Protocol's flat session mode: commands
 * carry an id (and a session id for a page), answers come back with the same id, and
 * events arrive in between. Once the socket closes every waiting command fails with
 * [WebViewUnavailableException], and the connection stays closed: [CdpBrowser] opens a
 * new one.
 */
class CdpConnection private constructor() : WebSocketListener() {
    private val log = LoggerFactory.getLogger(CdpConnection::class.java)
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val listeners = CopyOnWriteArrayList<(CdpEvent) -> Unit>()
    private val opened = CompletableDeferred<Unit>()

    /** Completes with the reason once the socket is closed. */
    val closed = CompletableDeferred<Throwable>()
    private lateinit var socket: WebSocket

    @Volatile
    private var closeReason: WebViewUnavailableException? = null

    val isOpen: Boolean get() = closeReason == null

    /**
     * Sends [method] and waits up to [timeout] for its result. Fails with
     * [CdpProtocolException] when the browser answers with an error.
     */
    suspend fun send(
        method: String,
        params: JsonObject = EMPTY,
        sessionId: String? = null,
        timeout: Duration = DEFAULT_TIMEOUT,
    ): JsonObject {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[id] = answer
        try {
            if (!isOpen) throw closedError()
            val message =
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("method", JsonPrimitive(method))
                    put("params", params)
                    sessionId?.let { put("sessionId", JsonPrimitive(it)) }
                }
            if (!socket.send(message.toString())) throw closedError()
            return withTimeoutOrNull(timeout.toMillis()) { answer.await() }
                ?: throw CdpTimeoutException("$method took longer than ${timeout.toSeconds()}s")
        } finally {
            pending.remove(id)
        }
    }

    /** Calls [listener] for every event until the returned handle is closed. */
    fun onEvent(listener: (CdpEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /**
     * Runs [trigger] and waits for the first event that [matches], listening from before
     * [trigger] runs so a fast event isn't missed.
     */
    suspend fun awaitEvent(
        timeout: Duration,
        matches: (CdpEvent) -> Boolean,
        trigger: suspend () -> Unit,
    ): CdpEvent {
        val event = CompletableDeferred<CdpEvent>()
        val handle = onEvent { if (matches(it)) event.complete(it) }
        val failure = closed.invokeOnCompletion { event.completeExceptionally(closedError()) }
        try {
            return withTimeoutOrNull(timeout.toMillis()) {
                trigger()
                event.await()
            } ?: throw CdpTimeoutException("no event within ${timeout.toSeconds()}s")
        } finally {
            handle.close()
            failure.dispose()
        }
    }

    fun close() {
        if (::socket.isInitialized) socket.close(NORMAL_CLOSURE, null)
        fail(WebViewUnavailableException("connection to the browser closed"))
    }

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        opened.complete(Unit)
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = (message["id"] as? JsonPrimitive)?.longOrNull
        if (id != null) {
            val answer = pending[id] ?: return
            val error = message["error"] as? JsonObject
            if (error != null) {
                answer.completeExceptionally(
                    CdpProtocolException(
                        (error["message"] as? JsonPrimitive)?.contentOrNull ?: "error",
                        (error["code"] as? JsonPrimitive)?.longOrNull,
                    ),
                )
            } else {
                answer.complete(message["result"] as? JsonObject ?: EMPTY)
            }
            return
        }
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val event = CdpEvent(method, message["params"] as? JsonObject ?: EMPTY, message.string("sessionId"))
        listeners.forEach { listener ->
            @Suppress("TooGenericExceptionCaught") // One broken listener must not stop the others.
            try {
                listener(event)
            } catch (e: RuntimeException) {
                log.warn("CDP event listener failed on {}", method, e)
            }
        }
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        webSocket.close(NORMAL_CLOSURE, null)
        fail(WebViewUnavailableException("the browser closed the connection"))
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        fail(WebViewUnavailableException("the browser closed the connection"))
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        val error = WebViewUnavailableException("connection to the browser failed: ${t.message ?: t.javaClass.simpleName}", t)
        opened.completeExceptionally(error)
        fail(error)
    }

    private fun fail(error: WebViewUnavailableException) {
        synchronized(this) {
            if (closeReason != null) return
            closeReason = error
        }
        closed.complete(error)
        opened.completeExceptionally(error)
        pending.values.forEach { it.completeExceptionally(error) }
    }

    private fun closedError(): WebViewUnavailableException = closeReason ?: WebViewUnavailableException("connection to the browser closed")

    companion object {
        val EMPTY = JsonObject(emptyMap())
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val NORMAL_CLOSURE = 1000

        /**
         * Opens a WebSocket to [request] and waits up to [timeout] for the handshake.
         * Fails with [WebViewUnavailableException] when the browser can't be reached.
         */
        suspend fun open(
            http: OkHttpClient,
            request: Request,
            timeout: Duration,
        ): CdpConnection {
            val connection = CdpConnection()
            connection.socket = http.newWebSocket(request, connection)
            var open = false
            try {
                open = withTimeoutOrNull(timeout.toMillis()) { connection.opened.await() } != null
            } finally {
                if (!open) {
                    // Timed out or cancelled: don't leave the handshake running.
                    connection.socket.cancel()
                    connection.fail(WebViewUnavailableException("the browser didn't answer within ${timeout.toSeconds()}s"))
                }
            }
            if (!open) throw connection.closedError()
            return connection
        }
    }
}

/** A string field of a JSON object, or null. */
internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

/** A string field of a JSON object, or a protocol error when it's missing. */
internal fun JsonObject.required(name: String): String = string(name) ?: throw CdpProtocolException("the browser's answer has no $name")
