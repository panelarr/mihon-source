// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

import android.graphics.Bitmap;

/**
 * WebView callbacks, called by the browser-backed {@link WebView}. Page events, URL loading
 * decisions and errors arrive on the main looper; {@code shouldInterceptRequest} is called on
 * a background thread, as on Android. As on Android, the newer overloads call the deprecated
 * ones by default, so extensions overriding either are served.
 */
public class WebViewClient {
    public static final int ERROR_UNKNOWN = -1;
    public static final int ERROR_HOST_LOOKUP = -2;
    public static final int ERROR_UNSUPPORTED_AUTH_SCHEME = -3;
    public static final int ERROR_AUTHENTICATION = -4;
    public static final int ERROR_PROXY_AUTHENTICATION = -5;
    public static final int ERROR_CONNECT = -6;
    public static final int ERROR_IO = -7;
    public static final int ERROR_TIMEOUT = -8;
    public static final int ERROR_REDIRECT_LOOP = -9;
    public static final int ERROR_UNSUPPORTED_SCHEME = -10;
    public static final int ERROR_FAILED_SSL_HANDSHAKE = -11;
    public static final int ERROR_BAD_URL = -12;
    public static final int ERROR_FILE = -13;
    public static final int ERROR_FILE_NOT_FOUND = -14;
    public static final int ERROR_TOO_MANY_REQUESTS = -15;
    public static final int ERROR_UNSAFE_RESOURCE = -16;

    public WebViewClient() {}

    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return shouldOverrideUrlLoading(view, request.getUrl().toString());
    }

    @Deprecated
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return false;
    }

    public void onPageStarted(WebView view, String url, Bitmap favicon) {}

    public void onPageFinished(WebView view, String url) {}

    public void onLoadResource(WebView view, String url) {}

    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return shouldInterceptRequest(view, request.getUrl().toString());
    }

    @Deprecated
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        return null;
    }

    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame()) {
            onReceivedError(view, error.getErrorCode(), String.valueOf(error.getDescription()), request.getUrl().toString());
        }
    }

    @Deprecated
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {}

    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {}

    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        return false;
    }
}
