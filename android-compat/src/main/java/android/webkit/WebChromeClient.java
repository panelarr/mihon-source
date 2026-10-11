// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

/** WebView UI callbacks. Never called on this host. */
public class WebChromeClient {
    public WebChromeClient() {}

    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        return false;
    }

    public void onProgressChanged(WebView view, int newProgress) {}

    public void onReceivedTitle(WebView view, String title) {}

    public boolean onJsAlert(WebView view, String url, String message, Object result) {
        return false;
    }
}
