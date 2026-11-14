// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration

/**
 * One browser tab, attached as a flat session on a [CdpConnection]. Pages are opened and
 * closed by [CdpBrowser.withPage], which also enforces the page limit.
 */
class CdpPage internal constructor(
    private val connection: CdpConnection,
    val targetId: String,
    val sessionId: String,
    /** The browser context the page lives in: its cookies and storage. */
    val browserContextId: String?,
    private val loadTimeout: Duration,
    private val loaded: (outcome: String) -> Unit = {},
) {
    /** Sends a command to this page. */
    suspend fun send(
        method: String,
        params: JsonObject = CdpConnection.EMPTY,
        timeout: Duration = CdpConnection.DEFAULT_TIMEOUT,
    ): JsonObject = connection.send(method, params, sessionId, timeout)

    /** Sends a command to the browser itself, such as `Storage.*` for this page's context. */
    suspend fun sendToBrowser(
        method: String,
        params: JsonObject = CdpConnection.EMPTY,
        timeout: Duration = CdpConnection.DEFAULT_TIMEOUT,
    ): JsonObject = connection.send(method, params, timeout = timeout)

    /** Whether the connection the page lives on is still open. */
    val isOpen: Boolean get() = connection.isOpen

    /** Calls [listener] for this page's events until the returned handle is closed. */
    fun onEvent(listener: (CdpEvent) -> Unit): AutoCloseable = connection.onEvent { if (it.sessionId == sessionId) listener(it) }

    /**
     * Loads [url] and waits for the page's load event, at most the load timeout overall.
     * Fails with [CdpLoadException] when the browser can't load it and with
     * [CdpTimeoutException] when it takes too long (the load is stopped then).
     */
    suspend fun navigate(url: String) {
        val loadFired = CompletableDeferred<Unit>()
        val handle = onEvent { if (it.method == LOAD_EVENT) loadFired.complete(Unit) }
        try {
            val done =
                withTimeoutOrNull(loadTimeout.toMillis()) {
                    // The send may wait longer than the load: the overall timeout above always fires first,
                    // so the load is stopped below instead of the send failing on its own.
                    val result =
                        send("Page.navigate", buildJsonObject { put("url", JsonPrimitive(url)) }, loadTimeout.plus(STOP_TIMEOUT))
                    result.string("errorText")?.let { throw CdpLoadException("the page didn't load: $it") }
                    // Without a loader the navigation stayed in the same document: no load event follows.
                    if (result.string("loaderId") != null) loadFired.await()
                }
            if (done == null) {
                runCatching { send("Page.stopLoading", timeout = STOP_TIMEOUT) }
                throw CdpTimeoutException("the page took longer than ${loadTimeout.toSeconds()}s to load")
            }
            loaded("ok")
        } catch (e: CdpTimeoutException) {
            loaded("timeout")
            throw e
        } catch (e: WebViewException) {
            loaded("error")
            throw e
        } finally {
            handle.close()
        }
    }

    /**
     * Runs [expression] in the page and returns its value as JSON (awaiting a promise),
     * or null for `undefined`. Fails with [CdpProtocolException] when the script throws.
     */
    suspend fun evaluate(expression: String): JsonElement? {
        val result =
            send(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", JsonPrimitive(expression))
                    put("returnByValue", JsonPrimitive(true))
                    put("awaitPromise", JsonPrimitive(true))
                },
                loadTimeout,
            )
        (result["exceptionDetails"] as? JsonObject)?.let { details ->
            val description = (details["exception"] as? JsonObject)?.string("description") ?: details.string("text")
            throw CdpProtocolException("the script failed: ${description?.lineSequence()?.firstOrNull() ?: "error"}")
        }
        return (result["result"] as? JsonObject)?.get("value")
    }

    internal suspend fun close() {
        if (!connection.isOpen) return
        runCatching {
            connection.send("Target.closeTarget", buildJsonObject { put("targetId", JsonPrimitive(targetId)) }, timeout = STOP_TIMEOUT)
        }
    }

    private companion object {
        const val LOAD_EVENT = "Page.loadEventFired"
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
