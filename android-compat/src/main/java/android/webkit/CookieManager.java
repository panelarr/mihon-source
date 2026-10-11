// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

import java.net.HttpCookie;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import panelarr.mihonsource.compat.AndroidHost;
import panelarr.mihonsource.compat.CookieListener;

/**
 * Android's CookieManager as an in-memory cookie store shared by the whole process. Cookies
 * follow the usual domain, path, expiry and secure rules. Every {@link #setCookie} is also
 * passed to {@link AndroidHost#getCookieListener()}, so the host can copy cookies into its own
 * cookie jars.
 */
public abstract class CookieManager {
    private static final CookieManager INSTANCE = new MemoryCookieManager();

    protected CookieManager() {}

    public static CookieManager getInstance() {
        return INSTANCE;
    }

    public abstract void setAcceptCookie(boolean accept);

    public abstract boolean acceptCookie();

    public abstract void setAcceptThirdPartyCookies(WebView webview, boolean accept);

    public abstract boolean acceptThirdPartyCookies(WebView webview);

    public abstract void setCookie(String url, String value);

    public abstract void setCookie(String url, String value, ValueCallback<Boolean> callback);

    public abstract String getCookie(String url);

    @Deprecated
    public abstract void removeSessionCookie();

    public abstract void removeSessionCookies(ValueCallback<Boolean> callback);

    @Deprecated
    public abstract void removeAllCookie();

    public abstract void removeAllCookies(ValueCallback<Boolean> callback);

    @Deprecated
    public abstract void removeExpiredCookie();

    public abstract boolean hasCookies();

    public abstract void flush();

    public static boolean allowFileSchemeCookies() {
        return false;
    }

    public static void setAcceptFileSchemeCookies(boolean accept) {}

    private static final class MemoryCookieManager extends CookieManager {
        private record Stored(HttpCookie cookie, String domain, boolean hostOnly, long expiresAt) {
            boolean expired(long now) {
                return expiresAt >= 0 && now >= expiresAt;
            }
        }

        private final List<Stored> cookies = new ArrayList<>();
        private volatile boolean accept = true;

        @Override
        public void setAcceptCookie(boolean accept) {
            this.accept = accept;
        }

        @Override
        public boolean acceptCookie() {
            return accept;
        }

        @Override
        public void setAcceptThirdPartyCookies(WebView webview, boolean accept) {}

        @Override
        public boolean acceptThirdPartyCookies(WebView webview) {
            return false;
        }

        private static URI uri(String url) {
            try {
                URI uri = URI.create(url.contains("://") ? url : "http://" + url);
                return uri.getHost() == null ? null : uri;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static String defaultPath(URI uri) {
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/")) return "/";
            int slash = path.lastIndexOf('/');
            return slash <= 0 ? "/" : path.substring(0, slash);
        }

        private static boolean domainMatches(String host, Stored stored) {
            if (stored.hostOnly) return host.equals(stored.domain);
            return host.equals(stored.domain) || host.endsWith("." + stored.domain);
        }

        private static boolean pathMatches(String requestPath, String cookiePath) {
            if (requestPath.equals(cookiePath)) return true;
            if (!requestPath.startsWith(cookiePath)) return false;
            return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
        }

        @Override
        public void setCookie(String url, String value) {
            setCookie(url, value, null);
        }

        @Override
        public void setCookie(String url, String value, ValueCallback<Boolean> callback) {
            boolean stored = accept && store(url, value);
            if (stored) {
                CookieListener listener = AndroidHost.INSTANCE.getCookieListener();
                if (listener != null) listener.onCookieSet(url, value);
            }
            if (callback != null) callback.onReceiveValue(stored);
        }

        private boolean store(String url, String value) {
            URI uri = url == null || value == null ? null : uri(url);
            if (uri == null) return false;
            List<HttpCookie> parsed;
            try {
                parsed = HttpCookie.parse("set-cookie:" + value);
            } catch (IllegalArgumentException e) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            long now = System.currentTimeMillis();
            synchronized (cookies) {
                for (HttpCookie cookie : parsed) {
                    String domain = cookie.getDomain();
                    boolean hostOnly = domain == null || domain.isEmpty();
                    String effective = hostOnly ? host : domain.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
                    if (!hostOnly && !(host.equals(effective) || host.endsWith("." + effective))) continue;
                    if (cookie.getPath() == null) cookie.setPath(defaultPath(uri));
                    long maxAge = cookie.getMaxAge();
                    long expiresAt = maxAge < 0 ? -1 : now + maxAge * 1000L;
                    cookies.removeIf(s -> s.cookie.getName().equals(cookie.getName()) && s.domain.equals(effective)
                        && s.cookie.getPath().equals(cookie.getPath()));
                    Stored entry = new Stored(cookie, effective, hostOnly, expiresAt);
                    if (!entry.expired(now)) cookies.add(entry);
                }
            }
            return true;
        }

        /** The cookies for {@code url} as a Cookie header value ("a=1; b=2"), or null. */
        @Override
        public String getCookie(String url) {
            URI uri = url == null ? null : uri(url);
            if (uri == null) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            boolean secure = "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme());
            long now = System.currentTimeMillis();
            List<String> pairs = new ArrayList<>();
            synchronized (cookies) {
                Iterator<Stored> it = cookies.iterator();
                while (it.hasNext()) {
                    Stored stored = it.next();
                    if (stored.expired(now)) {
                        it.remove();
                        continue;
                    }
                    if (!domainMatches(host, stored) || !pathMatches(path, stored.cookie.getPath())) continue;
                    if (stored.cookie.getSecure() && !secure) continue;
                    pairs.add(stored.cookie.getName() + "=" + stored.cookie.getValue());
                }
            }
            return pairs.isEmpty() ? null : String.join("; ", pairs);
        }

        @Override
        public void removeSessionCookie() {
            removeSessionCookies(null);
        }

        @Override
        public void removeSessionCookies(ValueCallback<Boolean> callback) {
            boolean removed;
            synchronized (cookies) {
                removed = cookies.removeIf(s -> s.expiresAt < 0);
            }
            if (callback != null) callback.onReceiveValue(removed);
        }

        @Override
        public void removeAllCookie() {
            removeAllCookies(null);
        }

        @Override
        public void removeAllCookies(ValueCallback<Boolean> callback) {
            boolean had;
            synchronized (cookies) {
                had = !cookies.isEmpty();
                cookies.clear();
            }
            CookieListener listener = AndroidHost.INSTANCE.getCookieListener();
            if (listener != null) listener.onCookiesCleared();
            if (callback != null) callback.onReceiveValue(had);
        }

        @Override
        public void removeExpiredCookie() {
            long now = System.currentTimeMillis();
            synchronized (cookies) {
                cookies.removeIf(s -> s.expired(now));
            }
        }

        @Override
        public boolean hasCookies() {
            synchronized (cookies) {
                return !cookies.isEmpty();
            }
        }

        /** Cookies live in memory only, so there is nothing to write. */
        @Override
        public void flush() {}
    }
}
