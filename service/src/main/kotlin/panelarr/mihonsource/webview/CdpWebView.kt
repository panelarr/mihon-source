// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import panelarr.mihonsource.compat.WebViewEngine
import panelarr.mihonsource.compat.WebViewProvider
import panelarr.mihonsource.config.WebViewConfig
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Backs every `android.webkit.WebView` with a page in the optional browser. */
class CdpWebViewProvider(
    private val browser: CdpBrowser,
    private val config: WebViewConfig,
) : WebViewProvider {
    private val ids = AtomicLong()

    override fun create(view: WebView): WebViewEngine = CdpWebView(view, browser, "webview-${ids.incrementAndGet()}", config.loadTimeout)
}

/**
 * One WebView, driving one page of the browser over CDP.
 *
 * **Threading.** Extensions use a WebView the Android way: they create and call it on the
 * main looper (`Handler(Looper.getMainLooper())`, `Dispatchers.Main`) and block their own
 * thread, the one the source call runs on, until a callback tells them the result. That
 * thread is one of the service's I/O threads, never an RPC thread. So:
 * - every WebView method only queues a command and returns, from whatever thread it's called
 *   on: the main looper never waits for the browser;
 * - commands run in order on this view's own coroutine (I/O dispatcher), which talks CDP;
 * - page callbacks, URL-loading decisions and `evaluateJavascript` results are posted to the
 *   main looper; `shouldInterceptRequest` and JavaScript interface methods run on an I/O
 *   thread, as Android runs them on background threads.
 *
 * The page opens on the first command, in a browser context of its own (so views share no
 * cookies or storage except through `CookieManager`), and closes on [destroy]. A view left
 * idle for [idleLimit] gives its page back; the next command opens a fresh one.
 */
internal class CdpWebView(
    private val view: WebView,
    private val browser: CdpBrowser,
    private val contextKey: String,
    private val loadTimeout: Duration,
    private val idleLimit: Duration = loadTimeout,
    private val main: Handler = Handler(Looper.getMainLooper()),
) : WebViewEngine {
    private sealed interface Command

    private data class Load(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        /** Served as the main document instead of fetching [url] (loadDataWithBaseURL). */
        val document: Document? = null,
    ) : Command

    private data class Document(
        val data: String,
        val mimeType: String,
    )

    private data class Evaluate(
        val script: String,
        val callback: ValueCallback<String>?,
    ) : Command

    private data class AddInterface(
        val name: String,
    ) : Command

    private data class RemoveInterface(
        val name: String,
        val bridge: JsBridge,
    ) : Command

    private data object Stop : Command

    private val log = LoggerFactory.getLogger(CdpWebView::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("webview-$contextKey"))
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val bridges = ConcurrentHashMap<String, JsBridge>()

    @Volatile private var destroyed = false

    @Volatile override var url: String? = null
        private set

    @Volatile override var originalUrl: String? = null
        private set

    @Volatile override var title: String? = null
        private set

    @Volatile override var progress: Int = 0
        private set

    init {
        scope.launch { run() }
    }

    override fun loadUrl(
        url: String,
        headers: Map<String, String>,
    ) = queue(Load(url, headers))

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        val type = mimeType?.takeIf { it.isNotBlank() } ?: "text/html"
        if (baseUrl != null && isHttp(baseUrl)) {
            queue(Load(baseUrl, document = Document(data, type)))
        } else {
            // No http(s) origin to borrow: a data URL, which has an opaque origin like Android's about:blank base.
            val encoded = Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))
            queue(Load("data:$type;charset=utf-8;base64,$encoded"))
        }
    }

    override fun evaluateJavascript(
        script: String,
        callback: ValueCallback<String>?,
    ) = queue(Evaluate(script, callback))

    override fun addJavascriptInterface(
        target: Any,
        name: String,
    ) {
        bridges[name] = JsBridge.of(target, name)
        queue(AddInterface(name))
    }

    override fun removeJavascriptInterface(name: String) {
        bridges.remove(name)?.let { queue(RemoveInterface(name, it)) }
    }

    override fun stopLoading() = queue(Stop)

    override fun reload() {
        (originalUrl ?: url)?.let { queue(Load(it)) }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        commands.close()
        scope.cancel()
    }

    private fun queue(command: Command) {
        if (!destroyed) commands.trySend(command)
    }

    /** Opens a page for the first command and serves commands until destroyed, reopening after idle closes. */
    private suspend fun run() {
        while (true) {
            val first = commands.receiveCatching().getOrNull() ?: return
            var session: Session? = null
            try {
                browser.withPage(contextKey) { page -> Session(page).also { session = it }.serve(first) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebViewException) {
                log.warn("A WebView couldn't use the browser: {}", e.message)
                // A session that started has answered its own commands; one that never opened owes the first.
                if (session == null) fail(first, e)
                // Whatever was queued meanwhile fails the same way; later commands try again.
                while (true) fail(commands.tryReceive().getOrNull() ?: break, e)
            } finally {
                withContext(NonCancellable) { runCatching { browser.closeContext(contextKey) } }
            }
        }
    }

    /** Tells the extension a command couldn't run: a load reports an error, a script returns null. */
    private fun fail(
        command: Command,
        error: WebViewException,
    ) {
        when (command) {
            is Load -> reportLoadError(command.url, WebViewClient.ERROR_CONNECT, "the browser service is unavailable: ${error.message}")
            is Evaluate -> post { command.callback?.onReceiveValue(NULL) }
            else -> Unit
        }
    }

    /** A page and what was set up on it. Lives until the view is destroyed or idle. */
    private inner class Session(
        val page: CdpPage,
    ) {
        private val pendingNavigation = AtomicReference<Load?>()
        private val inFlight = AtomicInteger()
        private val scriptIds = ConcurrentHashMap<String, String>()
        private var loadJob: Job? = null
        private var fetchEnabled = false
        private var userAgent: String? = null
        private var scriptsDisabled: Boolean? = null

        @Volatile private var lastActivity = System.nanoTime()

        suspend fun serve(first: Command) =
            coroutineScope {
                val events = Channel<CdpEvent>(Channel.UNLIMITED)
                val handle = listen(this, events)
                try {
                    launch { pageEvents(events) }
                    page.send("Runtime.enable")
                    bridges.keys.forEach { install(it) }
                    handle(this, first)
                    serveCommands(this)
                } finally {
                    handle.close()
                    events.close()
                }
                currentCoroutineContext()[Job]?.cancelChildren()
            }

        /**
         * Routes the page's events. Paused requests and binding calls are answered at once, never
         * queued behind page events: a paused request can hold up the very CDP calls a page
         * event's handling makes (a redirect the page starts on load, say).
         */
        private fun listen(
            session: CoroutineScope,
            events: Channel<CdpEvent>,
        ): AutoCloseable =
            page.onEvent { event ->
                when (event.method) {
                    "Fetch.requestPaused" -> {
                        touch()
                        inFlight.incrementAndGet()
                        session.launch {
                            try {
                                intercept(event.params)
                            } finally {
                                inFlight.decrementAndGet()
                                touch()
                            }
                        }
                    }

                    // Android calls interface methods on a background thread.
                    "Runtime.bindingCalled" -> {
                        touch()
                        session.launch { bindingCalled(event.params) }
                    }

                    "Page.frameNavigated", "Page.loadEventFired" -> {
                        events.trySend(event)
                    }
                }
            }

        private suspend fun pageEvents(events: Channel<CdpEvent>) {
            for (event in events) {
                try {
                    touch()
                    if (event.method == "Page.frameNavigated") onFrameNavigated(event.params) else onLoaded()
                } catch (e: WebViewUnavailableException) {
                    throw e
                } catch (e: WebViewException) {
                    log.debug("Couldn't handle {}: {}", event.method, e.message)
                }
            }
        }

        /** Runs commands as they come, until the view is destroyed or has been idle long enough. */
        private suspend fun serveCommands(scope: CoroutineScope) {
            while (true) {
                val received = withTimeoutOrNull(POLL.toMillis()) { commands.receiveCatching() }
                if (received == null) {
                    if (idle()) {
                        log.debug("Closing the page of a WebView idle for {}s", idleLimit.toSeconds())
                        return
                    }
                    continue
                }
                val command = received.getOrNull() ?: return
                touch()
                handle(scope, command)
            }
        }

        private fun touch() {
            lastActivity = System.nanoTime()
        }

        private fun idle(): Boolean =
            loadJob?.isActive != true && inFlight.get() == 0 && System.nanoTime() - lastActivity > idleLimit.toNanos()

        private suspend fun handle(
            scope: CoroutineScope,
            command: Command,
        ) {
            try {
                when (command) {
                    is Load -> {
                        loadJob?.cancel()
                        loadJob = scope.launch { load(command) }
                    }

                    is Evaluate -> {
                        evaluate(command)
                    }

                    is AddInterface -> {
                        install(command.name)
                    }

                    is RemoveInterface -> {
                        uninstall(command.name, command.bridge)
                    }

                    Stop -> {
                        loadJob?.cancel()
                        page.send("Page.stopLoading")
                    }
                }
            } catch (e: WebViewException) {
                fail(command, e)
                // The connection is gone: end the session, the next command reconnects.
                if (e is WebViewUnavailableException) throw e
            }
        }

        private suspend fun load(load: Load) {
            originalUrl = load.url
            progress = 0
            try {
                applySettings()
                if (!fetchEnabled && needsFetch(load)) {
                    page.send(
                        "Fetch.enable",
                        buildJsonObject {
                            put("patterns", jsonArrayOf(buildJsonObject { put("urlPattern", JsonPrimitive("*")) }))
                        },
                    )
                    fetchEnabled = true
                }
                if (isHttp(load.url)) WebViewCookies.push(page, load.url)
                pendingNavigation.set(load)
                page.navigate(load.url)
            } catch (e: CdpLoadException) {
                val text = e.message.orEmpty().substringAfter("load: ")
                // An aborted load was replaced or cancelled on purpose (stopLoading, an overridden redirect).
                if ("ERR_ABORTED" !in text) reportLoadError(load.url, errorCode(text), text)
            } catch (e: CdpTimeoutException) {
                reportLoadError(load.url, WebViewClient.ERROR_TIMEOUT, e.message.orEmpty())
            } catch (e: CdpProtocolException) {
                reportLoadError(load.url, WebViewClient.ERROR_UNKNOWN, e.message.orEmpty())
            } catch (e: WebViewUnavailableException) {
                fail(load, e)
                throw e
            } finally {
                pendingNavigation.set(null)
                touch()
            }
        }

        /** Whether [load] needs requests paused: for its headers or document, or for the client. */
        private fun needsFetch(load: Load): Boolean =
            load.headers.isNotEmpty() || load.document != null || interceptsRequests(view.webViewClient)

        /** The user agent and JavaScript switch from the view's settings, sent when they changed. */
        private suspend fun applySettings() {
            val settings = view.settings
            val agent = settings.userAgentString
            if (agent != userAgent) {
                page.send("Emulation.setUserAgentOverride", buildJsonObject { put("userAgent", JsonPrimitive(agent)) })
                userAgent = agent
            }
            val disabled = !settings.javaScriptEnabled
            if (disabled != scriptsDisabled) {
                page.send("Emulation.setScriptExecutionDisabled", buildJsonObject { put("value", JsonPrimitive(disabled)) })
                scriptsDisabled = disabled
            }
        }

        private suspend fun evaluate(command: Evaluate) {
            val result =
                try {
                    page.send(
                        "Runtime.evaluate",
                        buildJsonObject {
                            put("expression", JsonPrimitive(command.script))
                            put("returnByValue", JsonPrimitive(true))
                        },
                        loadTimeout,
                    )
                } catch (e: CdpProtocolException) {
                    // A value that can't be returned as JSON, for one: Android answers null.
                    log.debug("evaluateJavascript failed: {}", e.message)
                    null
                }
            val json = result?.let(::scriptResult) ?: NULL
            post { command.callback?.onReceiveValue(json) }
        }

        private suspend fun install(name: String) {
            val bridge = bridges[name] ?: return
            page.send("Runtime.addBinding", buildJsonObject { put("name", JsonPrimitive(bridge.binding)) })
            val added =
                page.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", JsonPrimitive(bridge.script())) })
            added.string("identifier")?.let { scriptIds[name] = it }
        }

        private suspend fun uninstall(
            name: String,
            bridge: JsBridge,
        ) {
            scriptIds.remove(name)?.let {
                page.send("Page.removeScriptToEvaluateOnNewDocument", buildJsonObject { put("identifier", JsonPrimitive(it)) })
            }
            page.send("Runtime.removeBinding", buildJsonObject { put("name", JsonPrimitive(bridge.binding)) })
        }

        private fun onFrameNavigated(params: JsonObject) {
            val frame = params["frame"] as? JsonObject
            val navigated = frame?.string("url")
            // Child frames and Chromium's own error pages aren't page loads to Android.
            if (navigated == null || frame.string("parentId") != null || isErrorPage(navigated)) return
            url = navigated
            progress = STARTED
            post { view.webViewClient.onPageStarted(view, navigated, null) }
        }

        private suspend fun onLoaded() {
            val loaded = url?.takeUnless(::isErrorPage) ?: return
            if (isHttp(loaded)) WebViewCookies.pull(page, loaded)
            title =
                try {
                    (page.evaluate("document.title") as? JsonPrimitive)?.content
                } catch (e: CdpProtocolException) {
                    log.debug("Couldn't read the page title: {}", e.message)
                    null
                }
            progress = DONE
            post { view.webViewClient.onPageFinished(view, loaded) }
        }

        private fun bindingCalled(params: JsonObject) {
            val name = params.string("name") ?: return
            val payload = params.string("payload") ?: return
            bridges.values.firstOrNull { it.binding == name }?.call(payload)
        }

        private suspend fun intercept(params: JsonObject) {
            val paused = PausedRequest.from(params, page.targetId) ?: return
            val ours = if (paused.mainFrame && !paused.redirect) pendingNavigation.getAndSet(null) else null
            try {
                val document = ours?.document
                when {
                    document != null -> {
                        val type = "${document.mimeType}; charset=utf-8"
                        page.fulfill(paused.id, document.data.toByteArray(Charsets.UTF_8), HTTP_OK, "OK", mapOf("Content-Type" to type))
                    }

                    // A navigation the page started, or a redirect: the extension may take it over.
                    paused.mainFrame && ours == null && overridden(paused) -> {
                        page.send(
                            "Fetch.failRequest",
                            buildJsonObject {
                                put("requestId", JsonPrimitive(paused.id))
                                put("errorReason", JsonPrimitive("Aborted"))
                            },
                        )
                    }

                    else -> {
                        respond(paused, ours?.headers.orEmpty())
                    }
                }
            } catch (e: WebViewException) {
                // The page or connection went away; the browser drops the request with it.
                log.debug("Couldn't answer an intercepted request: {}", e.message)
            }
        }

        private suspend fun overridden(paused: PausedRequest): Boolean {
            val request = paused.toWebResourceRequest(emptyMap())
            return onMain("shouldOverrideUrlLoading", false) { view.webViewClient.shouldOverrideUrlLoading(view, request) }
        }

        /** Answers with what `shouldInterceptRequest` returns, or lets the request through with [extraHeaders]. */
        private suspend fun respond(
            paused: PausedRequest,
            extraHeaders: Map<String, String>,
        ) {
            val request = paused.toWebResourceRequest(extraHeaders)
            val response = extension("shouldInterceptRequest", null) { view.webViewClient.shouldInterceptRequest(view, request) }
            if (response == null) {
                page.send(
                    "Fetch.continueRequest",
                    buildJsonObject {
                        put("requestId", JsonPrimitive(paused.id))
                        if (extraHeaders.isNotEmpty()) put("headers", headerArray(paused.headers + extraHeaders))
                    },
                )
                return
            }
            val body = response.data?.use { it.readAllBytes() } ?: ByteArray(0)
            val headers = response.responseHeaders.orEmpty().toMutableMap()
            if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) } && response.mimeType != null) {
                headers["Content-Type"] = response.mimeType + (response.encoding?.let { "; charset=$it" } ?: "")
            }
            page.fulfill(paused.id, body, response.statusCode, response.reasonPhrase, headers)
        }
    }

    private fun reportLoadError(
        failed: String,
        code: Int,
        description: String,
    ) {
        progress = DONE
        val request = WebViewRequest(failed, mainFrame = true)
        val error = WebViewError(code, description)
        post {
            view.webViewClient.onReceivedError(view, request, error)
            view.webViewClient.onPageFinished(view, failed)
        }
    }

    /** Runs [block] on the main looper, unless the view is destroyed by then. */
    private fun post(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    /** Runs [block] on the main looper and waits for its answer, at most the load timeout. */
    private suspend fun <T> onMain(
        what: String,
        fallback: T,
        block: () -> T,
    ): T {
        val answer = CompletableDeferred<T>()
        main.post { answer.complete(if (destroyed) fallback else extension(what, fallback, block)) }
        return withTimeoutOrNull(loadTimeout.toMillis()) { answer.await() } ?: fallback
    }

    /** Calls extension code; a throw is logged (never with values) and answered with [fallback]. */
    private fun <T> extension(
        what: String,
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            log.warn("The extension's {} failed: {}", what, e.javaClass.name)
            fallback
        }

    companion object {
        const val NULL = "null"
        private const val STARTED = 10
        private const val HTTP_OK = 200
        private const val DONE = 100
        private val POLL: Duration = Duration.ofSeconds(1)

        fun isHttp(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        private fun isErrorPage(url: String): Boolean = url.startsWith("chrome-error:")

        /** Whether [client] overrides the callbacks that need requests paused. */
        fun interceptsRequests(client: WebViewClient): Boolean {
            val type = client.javaClass
            val view = WebView::class.java
            return listOf(
                "shouldInterceptRequest" to android.webkit.WebResourceRequest::class.java,
                "shouldInterceptRequest" to String::class.java,
                "shouldOverrideUrlLoading" to android.webkit.WebResourceRequest::class.java,
                "shouldOverrideUrlLoading" to String::class.java,
            ).any { (name, arg) -> type.getMethod(name, view, arg).declaringClass != WebViewClient::class.java }
        }

        /** Android's error code for a Chromium net error such as `net::ERR_NAME_NOT_RESOLVED`. */
        fun errorCode(text: String): Int =
            when {
                "NAME_NOT_RESOLVED" in text || "NAME_RESOLUTION_FAILED" in text -> WebViewClient.ERROR_HOST_LOOKUP
                "TIMED_OUT" in text -> WebViewClient.ERROR_TIMEOUT
                "TOO_MANY_REDIRECTS" in text -> WebViewClient.ERROR_REDIRECT_LOOP
                "SSL" in text || "CERT_" in text -> WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
                "UNKNOWN_URL_SCHEME" in text -> WebViewClient.ERROR_UNSUPPORTED_SCHEME
                "INVALID_URL" in text -> WebViewClient.ERROR_BAD_URL
                "CONNECTION" in text || "ADDRESS_UNREACHABLE" in text || "PROXY" in text -> WebViewClient.ERROR_CONNECT
                else -> WebViewClient.ERROR_UNKNOWN
            }

        /**
         * `Runtime.evaluate`'s answer as Android's evaluateJavascript gives it: JSON, so a
         * string comes back quoted; undefined, functions, values JSON can't hold and a
         * thrown script give "null".
         */
        fun scriptResult(result: JsonObject): String {
            if (result["exceptionDetails"] != null) return NULL
            val value = result["result"] as? JsonObject ?: return NULL
            return when (value.string("type")) {
                "undefined", "function", "symbol" -> NULL
                else -> value["value"]?.toString() ?: NULL
            }
        }
    }
}
