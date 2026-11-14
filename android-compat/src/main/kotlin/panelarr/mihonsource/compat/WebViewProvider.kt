// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.webkit.ValueCallback
import android.webkit.WebView

/**
 * Makes the engine behind each `android.webkit.WebView`. The host installs one in
 * [AndroidHost.webViewProvider] when a browser is configured; without one, constructing a
 * WebView throws, as it always did.
 */
fun interface WebViewProvider {
    /** Called from the WebView's constructor, so it must not block. */
    fun create(view: WebView): WebViewEngine
}

/**
 * What a WebView does, behind Android's API. The WebView passes its calls through
 * unchanged; the engine reads the view's settings and client when it needs them.
 *
 * Threading: like Android's, every method returns at once, whatever thread calls it. The
 * work happens elsewhere, and the view's callbacks (WebViewClient page events,
 * evaluateJavascript results) are posted to the main looper. `shouldInterceptRequest` and
 * JavaScript interface methods run on a background thread, as on Android.
 */
interface WebViewEngine {
    fun loadUrl(
        url: String,
        headers: Map<String, String>,
    )

    fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?,
    )

    fun evaluateJavascript(
        script: String,
        callback: ValueCallback<String>?,
    )

    fun addJavascriptInterface(
        target: Any,
        name: String,
    )

    fun removeJavascriptInterface(name: String)

    fun stopLoading()

    fun reload()

    fun destroy()

    /** The main frame's URL, or null before the first page. */
    val url: String?

    /** The URL the current page was asked for, before redirects. */
    val originalUrl: String?

    /** The current page's title, or null. */
    val title: String?

    /** 0 to 100: how far the current load is. */
    val progress: Int
}
