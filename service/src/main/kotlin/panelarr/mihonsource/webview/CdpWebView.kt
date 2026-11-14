// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.slf4j.LoggerFactory
import panelarr.mihonsource.compat.SourceCaller
import panelarr.mihonsource.compat.WebViewEngine
import panelarr.mihonsource.compat.WebViewProvider
import panelarr.mihonsource.config.WebViewConfig
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Backs every `android.webkit.WebView` with a page in the optional browser. A WebView made
 * while working for a source ([SourceCaller]) uses that source's browser context, proxy, user
 * agent and cookie jar ([sources]); one made for no known source gets a context of its own.
 */
class CdpWebViewProvider(
    private val browser: CdpBrowser,
    private val config: WebViewConfig,
    private val sources: WebViewSources? = null,
    /** When each source's WebViews last found the browser unreachable. */
    val outages: WebViewOutages = WebViewOutages(),
) : WebViewProvider {
    private val ids = AtomicLong()

    override fun create(view: WebView): WebViewEngine =
        CdpWebView(
            view,
            browser,
            "webview-${ids.incrementAndGet()}",
            config.loadTimeout,
            source = sources?.let { found -> SourceCaller.current()?.let { CdpWebView.Owner(it, found) } },
            outages = outages,
        )
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
 * - commands run in order on this view's own coroutine, which talks CDP. It runs on
 *   [WebViewDispatchers.cdp], never on the I/O threads blocked source calls hold, so the
 *   callbacks they wait for can't starve;
 * - page callbacks, URL-loading decisions and `evaluateJavascript` results are posted to the
 *   main looper; `shouldInterceptRequest` and JavaScript interface methods run on
 *   [WebViewDispatchers.blocking], as Android runs them on background threads.
 *
 * Anything extension code throws in a callback is logged (by class only) and answered as
 * Android would: a throwing `shouldInterceptRequest` lets the request through, a broken
 * response stream fails that request. Nothing it throws ends the view.
 *
 * **Contexts.** A view made for a source opens its pages in that source's browser context,
 * which all its views share and which the browser closes when idle: created with the profile's
 * proxy, given the source's user agent unless the extension sets its own, and kept in step with
 * the source's cookie jar (the jar goes in before each load, the context's cookies come back
 * after loads and scripts). Loads of one host in one context take turns ([CdpBrowser.coalesced]).
 * A view for no known source gets a context of its own, closed with its page, whose cookies go
 * through `CookieManager`'s shared store as before.
 *
 * The page opens on the first command and closes on [destroy]. A view left idle for
 * [idleLimit] gives its page back; the next command opens a fresh one.
 */
internal class CdpWebView(
    private val view: WebView,
    private val browser: CdpBrowser,
    private val contextKey: String,
    private val loadTimeout: Duration,
    private val idleLimit: Duration = loadTimeout,
    private val main: Handler = Handler(Looper.getMainLooper()),
    /** The source the view works for, or null for none. */
    private val source: Owner? = null,
    private val outages: WebViewOutages? = null,
) : WebViewEngine {
    /** The source a view works for, and where its settings come from. */
    class Owner(
        val sourceId: Long,
        val sources: WebViewSources,
    )

    /** Where a session's page lives and what it takes from the source, fixed when the page opens. */
    private class Binding(
        val contextKey: String,
        val proxyServer: String? = null,
        val proxyCredentials: Pair<String, String>? = null,
        val cookies: JarCookieSync? = null,
        val userAgent: ((host: String) -> String)? = null,
    ) {
        /** A context for one view only, closed with its page. */
        val ownContext: Boolean get() = cookies == null
    }

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

    /** One navigation of a [Load]: a token of its own, as a reload queues the same load again. */
    private class Navigation(
        val load: Load,
    )

    private val log = LoggerFactory.getLogger(CdpWebView::class.java)
    private val scope = CoroutineScope(SupervisorJob() + WebViewDispatchers.cdp + CoroutineName("webview-$contextKey"))
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val bridges = ConcurrentHashMap<String, JsBridge>()

    @Volatile private var destroyed = false

    /** The last load the extension asked for, so [reload] repeats its headers and document. */
    @Volatile private var lastLoad: Load? = null

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
    ) = load(Load(url, headers))

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    ) {
        val type = mimeType?.takeIf { it.isNotBlank() } ?: "text/html"
        if (baseUrl != null && isHttp(baseUrl)) {
            load(Load(baseUrl, document = Document(data, type)))
        } else {
            // No http(s) origin to borrow: a data URL, which has an opaque origin like Android's about:blank base.
            val encoded = Base64.getEncoder().encodeToString(data.toByteArray(Charsets.UTF_8))
            load(Load("data:$type;charset=utf-8;base64,$encoded"))
        }
    }

    private fun load(load: Load) {
        lastLoad = load
        queue(load)
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
        val last = lastLoad
        val current = url
        // The extension's own load comes back whole (headers, document); a page the site moved on to by URL.
        when {
            last != null && (current == null || current == last.url) -> queue(last)
            current != null -> queue(Load(current))
        }
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
            var binding: Binding? = null
            try {
                val bound = bind().also { binding = it }
                browser.withPage(bound.contextKey, bound.proxyServer) { page -> Session(page, bound).also { session = it }.serve(first) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WebViewException) {
                log.warn("A WebView couldn't use the browser: {}", e.message)
                failPending(first.takeIf { session == null }, reason(e))
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                // Never let one failure end the view: its later commands would go unanswered for good.
                if (fatal(e)) throw e
                log.error("A WebView's page failed: {}", e.javaClass.name)
                failPending(first.takeIf { session == null }, "the WebView failed")
            } finally {
                // A source's context outlives its views (the browser closes it when idle); a view's own doesn't.
                binding?.takeIf { it.ownContext }?.let { withContext(NonCancellable) { runCatching { browser.closeContext(it.contextKey) } } }
            }
        }
    }

    /** Where the next page opens, from the source's settings as they are now. */
    private fun bind(): Binding {
        val owner = source ?: return Binding(contextKey)
        val settings = owner.sources.settings(owner.sourceId)
        val proxy = settings.proxy
        val socks = proxy?.type == java.net.Proxy.Type.SOCKS
        if (socks && proxy.username != null) {
            throw WebViewSettingsException(
                "the browser can't use a SOCKS proxy that needs a username and password; use an HTTP proxy for this source's profile",
            )
        }
        val server = proxy?.let { "${if (socks) "socks5" else "http"}://${hostForUrl(it.host)}:${it.port}" }
        // A new proxy needs a new context (the browser fixes it at creation); the old one closes when idle.
        val key = "source-${owner.sourceId}" + (server?.let { "-" + digest("$it ${proxy.username.orEmpty()}") } ?: "")
        return Binding(
            contextKey = key,
            proxyServer = server,
            proxyCredentials = proxy?.username?.let { it to proxy.password.orEmpty() },
            cookies = JarCookieSync(settings.jar),
            userAgent = settings.userAgent,
        )
    }

    /** Why a command failed, for the extension; an unreachable browser is noted for the source's call. */
    private fun reason(e: WebViewException): String =
        when (e) {
            is WebViewUnavailableException -> {
                val message = "the browser service is unavailable: ${e.message}"
                source?.let { outages?.record(it.sourceId, e.message ?: "unreachable") }
                message
            }

            is WebViewSettingsException -> {
                e.message.orEmpty()
            }

            else -> {
                "the browser couldn't serve the WebView: ${e.message}"
            }
        }

    /**
     * Fails [first] (a session that never opened owes it an answer; one that started has
     * answered its own commands) and whatever was queued meanwhile. Later commands try again.
     */
    private fun failPending(
        first: Command?,
        reason: String,
    ) {
        first?.let { fail(it, reason) }
        while (true) fail(commands.tryReceive().getOrNull() ?: break, reason)
    }

    /** Tells the extension a command couldn't run: a load reports an error, a script returns null. */
    private fun fail(
        command: Command,
        reason: String,
    ) {
        when (command) {
            is Load -> reportLoadError(command.url, WebViewClient.ERROR_CONNECT, reason)
            is Evaluate -> post { command.callback?.onReceiveValue(NULL) }
            else -> Unit
        }
    }

    /** A page and what was set up on it. Lives until the view is destroyed or idle. */
    private inner class Session(
        val page: CdpPage,
        val binding: Binding,
    ) {
        private val pendingNavigation = AtomicReference<Navigation?>()
        private val inFlight = AtomicInteger()
        private val scriptIds = ConcurrentHashMap<String, String>()
        private var loadJob: Job? = null
        private var fetchEnabled = false
        private var userAgent: String? = null
        private var scriptsDisabled: Boolean? = null
        private var runtimeEnabled = false

        /** Requests the proxy credentials were given for: asked again means they were refused. */
        private val authAnswered = ConcurrentHashMap.newKeySet<String>()

        /** Whether the main frame shows Chromium's error page for a failed load, whose load event isn't Android's. */
        @Volatile private var showingError = false

        @Volatile private var lastActivity = System.nanoTime()

        suspend fun serve(first: Command) =
            coroutineScope {
                val events = Channel<CdpEvent>(Channel.UNLIMITED)
                val handle = listen(this, events)
                try {
                    launch { pageEvents(events) }
                    bridges.keys.forEach { install(it) }
                    handle(this, first)
                    serveCommands(this)
                } finally {
                    handle.close()
                    events.close()
                    // What the page set since the last load or script still reaches the jar.
                    withContext(NonCancellable) { pullCookies() }
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
                                guarded(event.method) { intercept(event.params) }
                            } finally {
                                inFlight.decrementAndGet()
                                touch()
                            }
                        }
                    }

                    // Proxy credentials, answered at once like paused requests.
                    "Fetch.authRequired" -> {
                        touch()
                        session.launch { guarded(event.method) { answerAuth(event.params) } }
                    }

                    // Android calls interface methods on a background thread.
                    "Runtime.bindingCalled" -> {
                        touch()
                        session.launch(WebViewDispatchers.blocking) { guarded(event.method) { bindingCalled(event.params) } }
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
                    guarded(event.method) { if (event.method == "Page.frameNavigated") onFrameNavigated(event.params) else onLoaded() }
                } catch (e: WebViewUnavailableException) {
                    throw e
                } catch (e: WebViewException) {
                    log.debug("Couldn't handle {}: {}", event.method, e.message)
                }
            }
        }

        /** Runs commands as they come, until the view is destroyed or has been idle long enough. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private suspend fun serveCommands(scope: CoroutineScope) {
            while (true) {
                // A select, not a timeout around receive: cancelling a receive can lose the command it took.
                val received =
                    select {
                        commands.onReceiveCatching { it }
                        onTimeout(POLL.toMillis()) { null }
                    }
                if (received == null) {
                    if (idle()) {
                        log.debug("Closing the page of a WebView idle for {}s", idleLimit.toSeconds())
                        // The next command opens a blank page: don't report this one's state any longer.
                        url = null
                        originalUrl = null
                        title = null
                        progress = 0
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
                        loadJob = scope.launch { coalesced(command) }
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
                fail(command, reason(e))
                // The connection is gone: end the session, the next command reconnects.
                if (e is WebViewUnavailableException) throw e
            }
        }

        /** Loads [load], taking turns with the context's other loads of the same host. */
        private suspend fun coalesced(load: Load) {
            val host = if (isHttp(load.url)) load.url.toHttpUrlOrNull()?.host else null
            if (host == null || binding.ownContext) {
                load(load)
            } else {
                browser.coalesced("${binding.contextKey} $host") { load(load) }
            }
        }

        private suspend fun load(load: Load) {
            originalUrl = load.url
            progress = 0
            var navigation: Navigation? = null
            try {
                applySettings(load.url)
                if (!fetchEnabled && needsFetch(load)) {
                    page.send(
                        "Fetch.enable",
                        buildJsonObject {
                            put("patterns", jsonArrayOf(buildJsonObject { put("urlPattern", JsonPrimitive("*")) }))
                            if (binding.proxyCredentials != null) put("handleAuthRequests", JsonPrimitive(true))
                        },
                    )
                    fetchEnabled = true
                }
                if (isHttp(load.url)) binding.cookies?.push(page, load.url) ?: WebViewCookies.push(page, load.url)
                navigation = Navigation(load)
                pendingNavigation.set(navigation)
                page.navigate(load.url)
                // Before the next load of this host starts: it should find what this one earned.
                if (binding.cookies != null) pullCookies()
            } catch (e: CdpLoadException) {
                val text = e.message.orEmpty().substringAfter("load: ")
                // An aborted load was replaced or cancelled on purpose (stopLoading, an overridden redirect).
                if ("ERR_ABORTED" !in text) reportLoadError(load.url, errorCode(text), text)
            } catch (e: CdpTimeoutException) {
                reportLoadError(load.url, WebViewClient.ERROR_TIMEOUT, e.message.orEmpty())
            } catch (e: CdpProtocolException) {
                reportLoadError(load.url, WebViewClient.ERROR_UNKNOWN, e.message.orEmpty())
            } catch (e: WebViewUnavailableException) {
                fail(load, reason(e))
                throw e
            } finally {
                // Only this load's: a load that replaced it may have set its own already.
                navigation?.let { pendingNavigation.compareAndSet(it, null) }
                touch()
            }
        }

        /** Whether [load] needs requests paused: for its headers or document, for the client, or for proxy credentials. */
        private fun needsFetch(load: Load): Boolean =
            load.headers.isNotEmpty() || load.document != null || interceptsRequests(view.webViewClient) ||
                binding.proxyCredentials != null

        /**
         * The user agent and JavaScript switch from the view's settings, sent when they changed.
         * A view whose extension left the user agent alone uses the source's, as its HTTP
         * client sends to [url]'s host: clearance cookies only work with the agent they were
         * issued for.
         */
        private suspend fun applySettings(url: String) {
            val settings = view.settings
            val own = settings.userAgentString
            val sourceAgent = binding.userAgent
            val agent =
                if (sourceAgent != null && own == WebSettings.getDefaultUserAgent(null)) {
                    sourceAgent(url.toHttpUrlOrNull()?.host.orEmpty())
                } else {
                    own
                }
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
            // A script may set cookies (document.cookie): they reach the jar before the extension reads it.
            if (binding.cookies != null) pullCookies()
            post { command.callback?.onReceiveValue(json) }
        }

        /** Copies the context's cookies into the source's jar; a failure only means they come back later. */
        suspend fun pullCookies() {
            val sync = binding.cookies ?: return
            try {
                sync.pull(page)
            } catch (e: WebViewException) {
                log.debug("Couldn't copy the page's cookies: {}", e.message)
            }
        }

        /** Gives the profile's credentials to the proxy, once per request; anything else is cancelled, as Android does. */
        private suspend fun answerAuth(params: JsonObject) {
            val id = params.string("requestId") ?: return
            val credentials = binding.proxyCredentials
            val fromProxy = (params["authChallenge"] as? JsonObject)?.string("source") == "Proxy"
            val provide = fromProxy && credentials != null && authAnswered.add(id)
            try {
                page.send(
                    "Fetch.continueWithAuth",
                    buildJsonObject {
                        put("requestId", JsonPrimitive(id))
                        put(
                            "authChallengeResponse",
                            buildJsonObject {
                                put("response", JsonPrimitive(if (provide) "ProvideCredentials" else "CancelAuth"))
                                if (provide) {
                                    put("username", JsonPrimitive(credentials!!.first))
                                    put("password", JsonPrimitive(credentials.second))
                                }
                            },
                        )
                    },
                )
            } catch (e: WebViewException) {
                log.debug("Couldn't answer an authentication request: {}", e.message)
            }
            if (fromProxy && !provide && credentials != null) log.warn("The proxy refused the credentials of a source's profile")
        }

        private suspend fun install(name: String) {
            val bridge = bridges[name] ?: return
            // Only once a binding needs it: sites can detect the Runtime domain.
            if (!runtimeEnabled) {
                page.send("Runtime.enable")
                runtimeEnabled = true
            }
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
            if (navigated == null || frame.string("parentId") != null) return
            // Chromium's own error page for a failed load isn't a page load to Android; the failure was reported.
            showingError = isErrorPage(navigated) || frame.string("unreachableUrl") != null
            if (showingError) return
            url = navigated
            progress = STARTED
            post { view.webViewClient.onPageStarted(view, navigated, null) }
        }

        private suspend fun onLoaded() {
            if (showingError) return
            val loaded = url?.takeUnless(::isErrorPage) ?: return
            if (binding.cookies != null) {
                pullCookies()
            } else if (isHttp(loaded)) {
                WebViewCookies.pull(page, loaded)
            }
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
            val ours = if (paused.mainFrame && !paused.redirect) pendingNavigation.getAndSet(null)?.load else null
            try {
                val document = ours?.document
                when {
                    document != null -> {
                        val type = "${document.mimeType}; charset=utf-8"
                        page.fulfill(paused.id, document.data.toByteArray(Charsets.UTF_8), HTTP_OK, "OK", mapOf("Content-Type" to type))
                    }

                    // A navigation the page started, or a redirect: the extension may take it over.
                    paused.mainFrame && ours == null && overridden(paused) -> {
                        page.abort(paused.id, "Aborted")
                    }

                    else -> {
                        respond(paused, ours?.headers.orEmpty())
                    }
                }
            } catch (e: WebViewException) {
                // The page or connection went away; the browser drops the request with it.
                log.debug("Couldn't answer an intercepted request: {}", e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                if (fatal(e)) throw e
                // Never leave the browser waiting on it: that request fails, the page goes on.
                log.warn("Couldn't answer an intercepted request: {}", e.javaClass.name)
                page.abort(paused.id, "Failed")
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
            val response =
                withContext(WebViewDispatchers.blocking) {
                    extension("shouldInterceptRequest", null) { view.webViewClient.shouldInterceptRequest(view, request) }
                }
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
            val body =
                withContext(WebViewDispatchers.blocking) {
                    extension("shouldInterceptRequest's response", null) { response.data?.use { it.readAllBytes() } ?: ByteArray(0) }
                }
            if (body == null) {
                // Android fails the request when the extension's stream breaks.
                page.abort(paused.id, "Failed")
                return
            }
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
        // Android shows the failed URL from now on, not the page before it.
        url = failed
        title = failed
        progress = DONE
        val request = WebViewRequest(failed, mainFrame = true)
        val error = WebViewError(code, description)
        post {
            view.webViewClient.onReceivedError(view, request, error)
            view.webViewClient.onPageFinished(view, failed)
        }
    }

    /**
     * Runs [block], logging anything but a WebView error or cancellation (by class only: it may
     * come from extension code) instead of letting it end the session.
     */
    private suspend fun guarded(
        what: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: WebViewException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            if (fatal(e)) throw e
            log.warn("Couldn't handle {}: {}", what, e.javaClass.name)
        }
    }

    /** Runs [block] on the main looper as working for the view's source, unless the view is destroyed by then. */
    private fun post(block: () -> Unit) {
        main.post(SourceCaller.bind(source?.sourceId) { if (!destroyed) block() })
    }

    /** Runs [block] on the main looper and waits for its answer, at most the load timeout. */
    private suspend fun <T> onMain(
        what: String,
        fallback: T,
        block: () -> T,
    ): T {
        val answer = CompletableDeferred<T>()
        main.post(
            SourceCaller.bind(source?.sourceId) {
                try {
                    answer.complete(if (destroyed) fallback else extension(what, fallback, block))
                } finally {
                    // A fatal error rethrown by [extension] still leaves the browser an answer.
                    answer.complete(fallback)
                }
            },
        )
        return withTimeoutOrNull(loadTimeout.toMillis()) { answer.await() } ?: fallback
    }

    /**
     * Calls extension code; anything it throws (an `Error` too, as a missing class or `TODO()`
     * gives) is logged by class only and answered with [fallback]. Only a broken JVM goes on.
     */
    private fun <T> extension(
        what: String,
        fallback: T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Throwable,
        ) {
            if (fatal(e)) throw e
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

        /** A host as it goes in a URL: IPv6 addresses in brackets. */
        private fun hostForUrl(host: String): String = if (':' in host && !host.startsWith("[")) "[$host]" else host

        /** A short stable name for [text], so context keys never carry proxy addresses or user names. */
        private fun digest(text: String): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray())
                .take(DIGEST_BYTES)
                .joinToString("") { "%02x".format(it) }

        private const val DIGEST_BYTES = 6

        /** A JVM that can't go on, as the source calls treat it; anything else a view survives. */
        private fun fatal(e: Throwable): Boolean = e is VirtualMachineError && e !is OutOfMemoryError && e !is StackOverflowError

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
