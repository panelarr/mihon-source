// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

/** A WebView's settings. The browser-backed WebView applies the user agent and whether JavaScript runs; the rest are stored only. */
public abstract class WebSettings {
    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_NO_CACHE = 2;
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;
    public static final int MIXED_CONTENT_ALWAYS_ALLOW = 0;
    public static final int MIXED_CONTENT_NEVER_ALLOW = 1;
    public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;

    private boolean javaScriptEnabled;
    private boolean domStorageEnabled;
    private boolean databaseEnabled;
    private boolean blockNetworkImage;
    private boolean blockNetworkLoads;
    private boolean loadWithOverviewMode;
    private boolean useWideViewPort;
    private String userAgentString = defaultUserAgent();
    private int cacheMode = LOAD_DEFAULT;

    protected WebSettings() {}

    private static String defaultUserAgent() {
        return "Mozilla/5.0 (Linux; Android " + android.os.Build.VERSION.RELEASE + "; K) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36";
    }

    public static String getDefaultUserAgent(android.content.Context context) {
        return defaultUserAgent();
    }

    public boolean getJavaScriptEnabled() {
        return javaScriptEnabled;
    }

    public void setJavaScriptEnabled(boolean flag) {
        javaScriptEnabled = flag;
    }

    public boolean getDomStorageEnabled() {
        return domStorageEnabled;
    }

    public void setDomStorageEnabled(boolean flag) {
        domStorageEnabled = flag;
    }

    public boolean getDatabaseEnabled() {
        return databaseEnabled;
    }

    public void setDatabaseEnabled(boolean flag) {
        databaseEnabled = flag;
    }

    public boolean getBlockNetworkImage() {
        return blockNetworkImage;
    }

    public void setBlockNetworkImage(boolean flag) {
        blockNetworkImage = flag;
    }

    public boolean getBlockNetworkLoads() {
        return blockNetworkLoads;
    }

    public void setBlockNetworkLoads(boolean flag) {
        blockNetworkLoads = flag;
    }

    public boolean getLoadWithOverviewMode() {
        return loadWithOverviewMode;
    }

    public void setLoadWithOverviewMode(boolean overview) {
        loadWithOverviewMode = overview;
    }

    public boolean getUseWideViewPort() {
        return useWideViewPort;
    }

    public void setUseWideViewPort(boolean use) {
        useWideViewPort = use;
    }

    public String getUserAgentString() {
        return userAgentString;
    }

    public void setUserAgentString(String ua) {
        userAgentString = ua == null ? defaultUserAgent() : ua;
    }

    public int getCacheMode() {
        return cacheMode;
    }

    public void setCacheMode(int mode) {
        cacheMode = mode;
    }

    public void setLoadsImagesAutomatically(boolean flag) {}

    public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {}

    public void setMixedContentMode(int mode) {}

    public void setSupportZoom(boolean support) {}

    public void setBuiltInZoomControls(boolean enabled) {}

    public void setDisplayZoomControls(boolean enabled) {}

    public void setAllowFileAccess(boolean allow) {}

    public void setAllowContentAccess(boolean allow) {}

    public void setMediaPlaybackRequiresUserGesture(boolean require) {}
}
