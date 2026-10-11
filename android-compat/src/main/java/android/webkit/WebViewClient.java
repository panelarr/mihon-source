// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

import android.graphics.Bitmap;

/** WebView callbacks. Never called on this host, as no WebView can be created. */
public class WebViewClient {
    public static final int ERROR_UNKNOWN = -1;
    public static final int ERROR_HOST_LOOKUP = -2;
    public static final int ERROR_CONNECT = -6;
    public static final int ERROR_TIMEOUT = -8;

    public WebViewClient() {}

    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return false;
    }

    @Deprecated
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return false;
    }

    public void onPageStarted(WebView view, String url, Bitmap favicon) {}

    public void onPageFinished(WebView view, String url) {}

    public void onLoadResource(WebView view, String url) {}

    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return null;
    }

    @Deprecated
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        return null;
    }

    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {}

    @Deprecated
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {}

    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {}

    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        return false;
    }
}
