// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

import java.util.Locale;

/** URL checks. */
public final class URLUtil {
    public URLUtil() {}

    private static boolean startsWith(String url, String prefix) {
        return url != null && url.length() >= prefix.length() && url.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    public static boolean isHttpUrl(String url) {
        return startsWith(url, "http://");
    }

    public static boolean isHttpsUrl(String url) {
        return startsWith(url, "https://");
    }

    public static boolean isNetworkUrl(String url) {
        return isHttpUrl(url) || isHttpsUrl(url);
    }

    public static boolean isFileUrl(String url) {
        return startsWith(url, "file://");
    }

    public static boolean isDataUrl(String url) {
        return startsWith(url, "data:");
    }

    public static boolean isJavaScriptUrl(String url) {
        return startsWith(url, "javascript:");
    }

    public static boolean isAboutUrl(String url) {
        return startsWith(url, "about:");
    }

    public static boolean isContentUrl(String url) {
        return startsWith(url, "content:");
    }

    /** True for the URL schemes a WebView can load. */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        return isNetworkUrl(url) || isFileUrl(url) || isAboutUrl(url) || isJavaScriptUrl(url) || isContentUrl(url)
            || isDataUrl(url) || startsWith(url, "file:///android_asset/") || startsWith(url, "file:///android_res/");
    }

    public static String guessFileName(String url, String contentDisposition, String mimeType) {
        String path = url == null ? "" : url.replaceAll("[?#].*$", "");
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty()) name = "downloadfile";
        if (!name.contains(".")) name += mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("text/html") ? ".html" : ".bin";
        return name;
    }
}
