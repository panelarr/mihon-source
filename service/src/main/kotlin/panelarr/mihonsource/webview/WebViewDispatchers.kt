// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Where WebView work runs. Source calls block threads of `Dispatchers.IO` while they wait for
 * a WebView callback, so the coroutines that produce those callbacks must never wait for one
 * of those threads: with enough calls blocked, every waiting call would hold until its own
 * timeout. Views made with `limitedParallelism` on the I/O dispatcher get threads of their
 * own, beyond its 64-thread limit, so blocked source calls can't use them up.
 */
internal object WebViewDispatchers {
    private const val CDP_THREADS = 16
    private const val BLOCKING_THREADS = 32
    private const val DISCOVERY_THREADS = 2

    /** CDP traffic and each view's command loop. It only suspends, so a few threads serve every view. */
    val cdp: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(CDP_THREADS, "webview-cdp")

    /**
     * Work that may block: extension code the browser waits on (`shouldInterceptRequest`,
     * JavaScript interfaces, response streams) and plain HTTP calls. It stays off [cdp].
     */
    val blocking: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(BLOCKING_THREADS, "webview-blocking")

    /**
     * Finding the browser's endpoint (a blocking HTTP call). Its own threads: extension code
     * on [blocking] can't hold up a reconnect, and a slow browser can't use up [blocking].
     */
    val discovery: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DISCOVERY_THREADS, "webview-discovery")
}
