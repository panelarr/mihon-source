// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

import android.content.Context;
import android.view.ViewGroup;
import java.util.Map;
import panelarr.mihonsource.compat.AndroidHost;
import panelarr.mihonsource.compat.WebViewEngine;
import panelarr.mihonsource.compat.WebViewProvider;

/**
 * Android's WebView, backed by the optional browser service. mihon-source has no browser
 * engine of its own: when the host has a browser it installs a {@link WebViewProvider}, and
 * each WebView drives a page in that browser. Without one, constructing a WebView throws
 * {@link UnsupportedOperationException} with a hint on how to enable the browser.
 *
 * <p>As on Android, every method returns at once; page callbacks and script results arrive
 * on the main looper. See {@link WebViewEngine} for the threading.
 */
public class WebView extends ViewGroup {
    public static final String MESSAGE_UNSUPPORTED =
        "WebView needs the optional browser service, which isn't configured: set MIHON_WEBVIEW_URL to a headless Chromium";

    private final WebViewEngine engine;
    private final WebSettings settings = new WebSettings() {};
    private volatile WebViewClient client = new WebViewClient();
    private volatile WebChromeClient chromeClient;

    public WebView(Context context) {
        super(context);
        WebViewProvider provider = AndroidHost.INSTANCE.getWebViewProvider();
        if (provider == null) throw new UnsupportedOperationException(MESSAGE_UNSUPPORTED);
        engine = provider.create(this);
    }

    public static ClassLoader getWebViewClassLoader() {
        return WebView.class.getClassLoader();
    }

    public static void setWebContentsDebuggingEnabled(boolean enabled) {}

    public static String findAddress(String addr) {
        return null;
    }

    public WebSettings getSettings() {
        return settings;
    }

    public void setWebViewClient(WebViewClient client) {
        this.client = client != null ? client : new WebViewClient();
    }

    public WebViewClient getWebViewClient() {
        return client;
    }

    public void setWebChromeClient(WebChromeClient client) {
        this.chromeClient = client;
    }

    public WebChromeClient getWebChromeClient() {
        return chromeClient;
    }

    /** Takes effect from the next page load, as on Android. Methods are one-way: they can't return values to the page. */
    public void addJavascriptInterface(Object object, String name) {
        if (object == null || name == null) return;
        engine.addJavascriptInterface(object, name);
    }

    public void removeJavascriptInterface(String name) {
        if (name != null) engine.removeJavascriptInterface(name);
    }

    public void loadUrl(String url) {
        loadUrl(url, Map.of());
    }

    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        if (url == null) return;
        engine.loadUrl(url, additionalHttpHeaders == null ? Map.of() : Map.copyOf(additionalHttpHeaders));
    }

    /** Loads {@code data} as a data: URL. With encoding "base64" the data is base64, otherwise URL-encoded. */
    public void loadData(String data, String mimeType, String encoding) {
        String type = mimeType == null || mimeType.isEmpty() ? "text/html" : mimeType;
        boolean base64 = "base64".equalsIgnoreCase(encoding);
        loadUrl("data:" + type + (base64 ? ";base64" : "") + "," + (data == null ? "" : data));
    }

    /** Shows {@code data} as if it had been loaded from {@code baseUrl}, so it gets that origin. */
    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {
        engine.loadDataWithBaseURL(baseUrl, data == null ? "" : data, mimeType, encoding, historyUrl);
    }

    /**
     * Runs {@code script} in the current page. The callback gets the result as JSON, as on
     * Android: a string comes back quoted, and undefined, functions and failures as "null".
     */
    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        if (script == null) return;
        engine.evaluateJavascript(script, resultCallback);
    }

    public void stopLoading() {
        engine.stopLoading();
    }

    public void reload() {
        engine.reload();
    }

    /** Closes the page in the browser. The WebView does nothing afterwards. */
    public void destroy() {
        engine.destroy();
    }

    public String getUrl() {
        return engine.getUrl();
    }

    public String getOriginalUrl() {
        return engine.getOriginalUrl();
    }

    public String getTitle() {
        return engine.getTitle();
    }

    public int getProgress() {
        return engine.getProgress();
    }

    public void clearCache(boolean includeDiskFiles) {}

    public void clearHistory() {}

    public void clearFormData() {}

    public void onPause() {}

    public void onResume() {}

    public void pauseTimers() {}

    public void resumeTimers() {}
}
