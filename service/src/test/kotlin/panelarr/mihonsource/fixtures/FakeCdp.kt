// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A made-up browser that speaks just enough of the Chrome DevTools Protocol for tests:
 * `/json/version` discovery, a browser WebSocket, targets, flat sessions and contexts.
 * Tests change answers with [handler] and push events with [emit].
 */
class FakeCdp : AutoCloseable {
    /** A command as the fake received it. */
    data class Command(
        val method: String,
        val params: JsonObject,
        val sessionId: String?,
    )

    /** What to do with a command: a result, an error ([Error]), or [Ignore] to never answer. */
    sealed interface Answer

    data class Result(
        val result: JsonObject = JsonObject(emptyMap()),
    ) : Answer

    data class Error(
        val message: String,
        val code: Int = -32000,
    ) : Answer

    data object Ignore : Answer

    private val server = MockWebServer()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val ids = AtomicInteger()

    val commands = CopyOnWriteArrayList<Command>()

    /** Host headers of every HTTP request, discovery and WebSocket upgrade alike. */
    val hostHeaders = CopyOnWriteArrayList<String>()
    val targets: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val contexts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val connections = AtomicInteger()

    /** When false, the WebSocket path answers 503, as a browser that's starting up might. */
    @Volatile var accepting = true

    /** Overrides the default answers; return null to fall back to them. */
    @Volatile var handler: (Command) -> Answer? = { null }

    /**
     * Sent after the answer to a command, e.g. load events after `Page.navigate`. Return the
     * events as (method, params) pairs.
     */
    @Volatile var after: (Command) -> List<Pair<String, JsonObject>> = { command ->
        if (command.method ==
            "Page.navigate"
        ) {
            listOf("Page.loadEventFired" to buildJsonObject { put("timestamp", JsonPrimitive(1)) })
        } else {
            emptyList()
        }
    }

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    request.headers["Host"]?.let(hostHeaders::add)
                    return when (request.url.encodedPath) {
                        "/json/version" -> {
                            // Like Chromium: the URL names the browser as it sees itself, not as clients reach it.
                            val body =
                                """{"Browser":"FakeChrome/1.0","webSocketDebuggerUrl":"ws://127.0.0.1:1/devtools/browser/fake-id"}"""
                            MockResponse.Builder().body(body).build()
                        }

                        "/devtools/browser/fake-id" -> {
                            if (accepting) {
                                MockResponse.Builder().webSocketUpgrade(Listener()).build()
                            } else {
                                MockResponse.Builder().code(503).build()
                            }
                        }

                        else -> {
                            MockResponse.Builder().code(404).build()
                        }
                    }
                }
            }
        server.start()
    }

    /** The URL mihon-source is configured with, e.g. `http://localhost:1234`. */
    val url: String get() = "http://${server.hostName}:${server.port}"
    val port: Int get() = server.port

    /** Commands received with this method. */
    fun sent(method: String): List<Command> = commands.filter { it.method == method }

    /** Sends an event to every open connection. */
    fun emit(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        sessionId: String? = null,
    ) {
        val message =
            buildJsonObject {
                put("method", JsonPrimitive(method))
                put("params", params)
                sessionId?.let { put("sessionId", JsonPrimitive(it)) }
            }.toString()
        sockets.forEach { it.send(message) }
    }

    /** Drops every open connection, as a browser that crashed or restarted would. */
    fun dropConnections() {
        // cancel() only works on client sockets; a server socket closes with a going-away frame.
        sockets.forEach { it.close(GOING_AWAY, "browser restarted") }
        sockets.clear()
        targets.clear()
        contexts.clear()
    }

    override fun close() {
        dropConnections()
        server.close()
    }

    private fun defaultAnswer(command: Command): Answer {
        val p = command.params
        return when (command.method) {
            "Browser.getVersion" -> {
                Result(buildJsonObject { put("product", JsonPrimitive("FakeChrome/1.0")) })
            }

            "Target.createBrowserContext" -> {
                val id = "context-${ids.incrementAndGet()}"
                contexts += id
                Result(buildJsonObject { put("browserContextId", JsonPrimitive(id)) })
            }

            "Target.disposeBrowserContext" -> {
                contexts -= p.text("browserContextId")
                Result()
            }

            "Target.createTarget" -> {
                val id = "target-${ids.incrementAndGet()}"
                targets += id
                Result(buildJsonObject { put("targetId", JsonPrimitive(id)) })
            }

            "Target.attachToTarget" -> {
                Result(buildJsonObject { put("sessionId", JsonPrimitive("session-" + p.text("targetId"))) })
            }

            "Target.closeTarget" -> {
                targets -= p.text("targetId")
                Result(buildJsonObject { put("success", JsonPrimitive(true)) })
            }

            "Page.navigate" -> {
                Result(
                    buildJsonObject {
                        put("frameId", JsonPrimitive("frame"))
                        put("loaderId", JsonPrimitive("loader-${ids.incrementAndGet()}"))
                    },
                )
            }

            "Runtime.evaluate" -> {
                Result(
                    buildJsonObject {
                        put(
                            "result",
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                put("value", JsonPrimitive("evaluated: " + p.text("expression")))
                            },
                        )
                    },
                )
            }

            else -> {
                Result()
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            sockets += webSocket
            connections.incrementAndGet()
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            val message = Json.parseToJsonElement(text).jsonObject
            val command =
                Command(
                    message.text("method"),
                    message["params"] as? JsonObject ?: JsonObject(emptyMap()),
                    (message["sessionId"] as? JsonPrimitive)?.content,
                )
            commands += command
            val id = message.getValue("id").jsonPrimitive.long
            val answer = handler(command) ?: defaultAnswer(command)
            val reply: JsonElement =
                when (answer) {
                    is Result -> {
                        buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put("result", answer.result)
                            command.sessionId?.let { put("sessionId", JsonPrimitive(it)) }
                        }
                    }

                    is Error -> {
                        buildJsonObject {
                            put("id", JsonPrimitive(id))
                            put(
                                "error",
                                buildJsonObject {
                                    put("code", JsonPrimitive(answer.code))
                                    put("message", JsonPrimitive(answer.message))
                                },
                            )
                        }
                    }

                    Ignore -> {
                        return
                    }
                }
            webSocket.send(reply.toString())
            after(command).forEach { (method, params) ->
                webSocket.send(
                    buildJsonObject {
                        put("method", JsonPrimitive(method))
                        put("params", params)
                        command.sessionId?.let { put("sessionId", JsonPrimitive(it)) }
                    }.toString(),
                )
            }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            sockets -= webSocket
            webSocket.close(code, null)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            sockets -= webSocket
        }
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private companion object {
        const val GOING_AWAY = 1001
    }
}
