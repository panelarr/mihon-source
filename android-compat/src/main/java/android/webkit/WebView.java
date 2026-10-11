// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

import android.content.Context;
import android.view.ViewGroup;
import java.util.Map;

/**
 * Android's WebView. There is no browser engine in mihon-source, so constructing one throws
 * {@link UnsupportedOperationException}; sources that need a WebView are unsupported. The
 * members exist so extension code links.
 */
public class WebView extends ViewGroup {
    public static final String MESSAGE_UNSUPPORTED = "WebView is not available in mihon-source";

    public WebView(Context context) {
        super(context);
        throw new UnsupportedOperationException(MESSAGE_UNSUPPORTED);
    }

    public static ClassLoader getWebViewClassLoader() {
        return WebView.class.getClassLoader();
    }

    public static void setWebContentsDebuggingEnabled(boolean enabled) {}

    public static String findAddress(String addr) {
        return null;
    }

    public WebSettings getSettings() {
        throw new UnsupportedOperationException(MESSAGE_UNSUPPORTED);
    }

    public void setWebViewClient(WebViewClient client) {}

    public void setWebChromeClient(WebChromeClient client) {}

    public void addJavascriptInterface(Object object, String name) {}

    public void removeJavascriptInterface(String name) {}

    public void loadUrl(String url) {}

    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {}

    public void loadData(String data, String mimeType, String encoding) {}

    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {}

    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {}

    public void stopLoading() {}

    public void reload() {}

    public void destroy() {}

    public String getUrl() {
        return null;
    }

    public String getTitle() {
        return null;
    }

    public void clearCache(boolean includeDiskFiles) {}

    public void clearHistory() {}

    public void onPause() {}

    public void onResume() {}
}
