// SPDX-License-Identifier: GPL-3.0-or-later

package android.net;

import android.os.Parcel;
import android.os.Parcelable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Android's Uri, written from its public documentation. Like Android, {@link #parse(String)}
 * never fails: the string is kept as given and split into parts on demand. Getters without
 * "Encoded" in their name return percent-decoded values.
 *
 * <p>A URI is hierarchical when it has no scheme, or when the part after "scheme:" starts
 * with '/'. Otherwise (for example "mailto:a@b.c") it is opaque and only has a scheme,
 * a scheme-specific part and a fragment.
 */
public abstract class Uri implements Parcelable, Comparable<Uri> {
    /** The empty URI, equivalent to {@code parse("")}. */
    public static final Uri EMPTY = new StringUri("");

    private static final String UNRESERVED = "_-!.~'()*";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    Uri() {}

    public static Uri parse(String uriString) {
        if (uriString == null) throw new NullPointerException("uriString");
        return new StringUri(uriString);
    }

    public static Uri fromFile(File file) {
        if (file == null) throw new NullPointerException("file");
        return new Builder().scheme("file").authority("").path(file.getAbsolutePath()).build();
    }

    public static Uri fromParts(String scheme, String ssp, String fragment) {
        if (scheme == null) throw new NullPointerException("scheme");
        if (ssp == null) throw new NullPointerException("ssp");
        StringBuilder sb = new StringBuilder(scheme).append(':').append(encode(ssp));
        if (fragment != null) sb.append('#').append(encode(fragment));
        return new StringUri(sb.toString());
    }

    public static Uri withAppendedPath(Uri baseUri, String pathSegment) {
        return baseUri.buildUpon().appendEncodedPath(pathSegment).build();
    }

    public abstract boolean isHierarchical();

    public boolean isOpaque() {
        return !isHierarchical();
    }

    public abstract boolean isRelative();

    public boolean isAbsolute() {
        return !isRelative();
    }

    public abstract String getScheme();

    public abstract String getSchemeSpecificPart();

    public abstract String getEncodedSchemeSpecificPart();

    public abstract String getAuthority();

    public abstract String getEncodedAuthority();

    public abstract String getUserInfo();

    public abstract String getEncodedUserInfo();

    public abstract String getHost();

    public abstract int getPort();

    public abstract String getPath();

    public abstract String getEncodedPath();

    public abstract String getQuery();

    public abstract String getEncodedQuery();

    public abstract String getFragment();

    public abstract String getEncodedFragment();

    public abstract List<String> getPathSegments();

    public abstract String getLastPathSegment();

    public abstract Builder buildUpon();

    @Override
    public abstract String toString();

    @Override
    public boolean equals(Object o) {
        return o instanceof Uri other && toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public int compareTo(Uri other) {
        return toString().compareTo(other.toString());
    }

    /** Lowercases the scheme, as RFC 3986 asks. */
    public Uri normalizeScheme() {
        String scheme = getScheme();
        if (scheme == null) return this;
        String lower = scheme.toLowerCase(Locale.ROOT);
        if (lower.equals(scheme)) return this;
        return parse(lower + toString().substring(scheme.length()));
    }

    /** Names of the query parameters, decoded, in order of first appearance. */
    public Set<String> getQueryParameterNames() {
        if (isOpaque()) throw new UnsupportedOperationException("This isn't a hierarchical URI.");
        String query = getEncodedQuery();
        if (query == null) return Collections.emptySet();
        Set<String> names = new LinkedHashSet<>();
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            names.add(decode(eq < 0 ? pair : pair.substring(0, eq)));
        }
        return Collections.unmodifiableSet(names);
    }

    /** All decoded values of {@code key}. Like Android, '+' is left as is here. */
    public List<String> getQueryParameters(String key) {
        List<String> values = new ArrayList<>();
        for (String raw : rawQueryValues(key)) values.add(decode(raw));
        return Collections.unmodifiableList(values);
    }

    /** The first decoded value of {@code key}, with '+' read as a space, or null. */
    public String getQueryParameter(String key) {
        List<String> values = rawQueryValues(key);
        if (values.isEmpty()) return null;
        return decode(values.get(0).replace('+', ' '));
    }

    public boolean getBooleanQueryParameter(String key, boolean defaultValue) {
        String value = getQueryParameter(key);
        if (value == null) return defaultValue;
        value = value.toLowerCase(Locale.ROOT);
        return !"false".equals(value) && !"0".equals(value);
    }

    private List<String> rawQueryValues(String key) {
        if (isOpaque()) throw new UnsupportedOperationException("This isn't a hierarchical URI.");
        if (key == null) throw new NullPointerException("key");
        String query = getEncodedQuery();
        List<String> values = new ArrayList<>();
        if (query == null) return values;
        String encodedKey = encode(key, null);
        for (String pair : query.split("&", -1)) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (name.equals(encodedKey) || decode(name).equals(key)) values.add(eq < 0 ? "" : pair.substring(eq + 1));
        }
        return values;
    }

    // --- encoding --------------------------------------------------------------------------

    public static String encode(String s) {
        return encode(s, null);
    }

    /**
     * Percent-encodes {@code s} as UTF-8. Letters, digits, {@code _-!.~'()*} and the characters
     * in {@code allow} are kept; everything else is escaped with uppercase hex digits.
     */
    public static String encode(String s, String allow) {
        if (s == null) return null;
        StringBuilder out = null;
        int length = s.length();
        for (int i = 0; i < length; ) {
            int cp = s.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (isAllowed(cp, allow)) {
                if (out != null) out.appendCodePoint(cp);
            } else {
                if (out == null) out = new StringBuilder(length + 16).append(s, 0, i);
                for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                    out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            }
            i = next;
        }
        return out == null ? s : out.toString();
    }

    private static boolean isAllowed(int c, String allow) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || UNRESERVED.indexOf(c) >= 0 || (allow != null && c < 0x80 && allow.indexOf(c) >= 0);
    }

    /** Decodes %XX escapes as UTF-8. '+' is kept, and malformed escapes stay as written. */
    public static String decode(String s) {
        if (s == null) return null;
        int percent = s.indexOf('%');
        if (percent < 0) return s;
        StringBuilder out = new StringBuilder(s.length()).append(s, 0, percent);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int i = percent;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                bytes.write((hex(s.charAt(i + 1)) << 4) | hex(s.charAt(i + 2)));
                i += 3;
                continue;
            }
            flush(bytes, out);
            out.append(c);
            i++;
        }
        flush(bytes, out);
        return out.toString();
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) return;
        out.append(bytes.toString(StandardCharsets.UTF_8));
        bytes.reset();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(toString());
    }

    public static void writeToParcel(Parcel out, Uri uri) {
        out.writeString(uri == null ? null : uri.toString());
    }

    // --- the only implementation -------------------------------------------------------------

    private static final class StringUri extends Uri {
        private final String uri;
        private final int schemeEnd; // index of the scheme's ':' or -1
        private final int fragmentStart; // index of '#' or -1

        StringUri(String uri) {
            this.uri = uri;
            this.schemeEnd = findSchemeEnd(uri);
            this.fragmentStart = uri.indexOf('#');
        }

        private static int findSchemeEnd(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == ':') return i == 0 ? -1 : i;
                if (c == '/' || c == '?' || c == '#') return -1;
            }
            return -1;
        }

        /** The part between "scheme:" and '#'. */
        private String ssp() {
            int end = fragmentStart < 0 ? uri.length() : fragmentStart;
            int start = schemeEnd + 1;
            return start > end ? "" : uri.substring(start, end);
        }

        @Override
        public boolean isHierarchical() {
            if (schemeEnd < 0) return true;
            return uri.length() > schemeEnd + 1 && uri.charAt(schemeEnd + 1) == '/';
        }

        @Override
        public boolean isRelative() {
            return schemeEnd < 0;
        }

        @Override
        public String getScheme() {
            return schemeEnd < 0 ? null : uri.substring(0, schemeEnd);
        }

        @Override
        public String getSchemeSpecificPart() {
            return decode(ssp());
        }

        @Override
        public String getEncodedSchemeSpecificPart() {
            return ssp();
        }

        @Override
        public String getEncodedAuthority() {
            if (!isHierarchical()) return null;
            String ssp = ssp();
            if (!ssp.startsWith("//")) return null;
            int end = 2;
            while (end < ssp.length() && "/?".indexOf(ssp.charAt(end)) < 0) end++;
            return ssp.substring(2, end);
        }

        @Override
        public String getAuthority() {
            return decode(getEncodedAuthority());
        }

        @Override
        public String getEncodedUserInfo() {
            String authority = getEncodedAuthority();
            if (authority == null) return null;
            int at = authority.lastIndexOf('@');
            return at < 0 ? null : authority.substring(0, at);
        }

        @Override
        public String getUserInfo() {
            return decode(getEncodedUserInfo());
        }

        /** The authority without user info, e.g. "example.org:8080" or "[::1]:80". */
        private String hostAndPort() {
            String authority = getEncodedAuthority();
            if (authority == null) return null;
            return authority.substring(authority.lastIndexOf('@') + 1);
        }

        private int portSeparator(String hostAndPort) {
            int colon = hostAndPort.lastIndexOf(':');
            int bracket = hostAndPort.lastIndexOf(']');
            return colon > bracket ? colon : -1;
        }

        @Override
        public String getHost() {
            String hostAndPort = hostAndPort();
            if (hostAndPort == null) return null;
            int colon = portSeparator(hostAndPort);
            return decode(colon < 0 ? hostAndPort : hostAndPort.substring(0, colon));
        }

        @Override
        public int getPort() {
            String hostAndPort = hostAndPort();
            if (hostAndPort == null) return -1;
            int colon = portSeparator(hostAndPort);
            if (colon < 0) return -1;
            try {
                return Integer.parseInt(decode(hostAndPort.substring(colon + 1)));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        @Override
        public String getEncodedPath() {
            if (!isHierarchical()) return null;
            String ssp = ssp();
            int start = 0;
            if (ssp.startsWith("//")) {
                start = 2;
                while (start < ssp.length() && "/?".indexOf(ssp.charAt(start)) < 0) start++;
            }
            int query = ssp.indexOf('?', start);
            return ssp.substring(start, query < 0 ? ssp.length() : query);
        }

        @Override
        public String getPath() {
            return decode(getEncodedPath());
        }

        @Override
        public String getEncodedQuery() {
            if (!isHierarchical()) return null;
            String ssp = ssp();
            int start = 0;
            if (ssp.startsWith("//")) {
                start = 2;
                while (start < ssp.length() && "/?".indexOf(ssp.charAt(start)) < 0) start++;
            }
            int query = ssp.indexOf('?', start);
            return query < 0 ? null : ssp.substring(query + 1);
        }

        @Override
        public String getQuery() {
            return decode(getEncodedQuery());
        }

        @Override
        public String getEncodedFragment() {
            return fragmentStart < 0 ? null : uri.substring(fragmentStart + 1);
        }

        @Override
        public String getFragment() {
            return decode(getEncodedFragment());
        }

        @Override
        public List<String> getPathSegments() {
            String path = getEncodedPath();
            if (path == null) return Collections.emptyList();
            List<String> segments = new ArrayList<>();
            for (String segment : path.split("/")) {
                if (!segment.isEmpty()) segments.add(decode(segment));
            }
            return Collections.unmodifiableList(segments);
        }

        @Override
        public String getLastPathSegment() {
            List<String> segments = getPathSegments();
            return segments.isEmpty() ? null : segments.get(segments.size() - 1);
        }

        @Override
        public Builder buildUpon() {
            if (isHierarchical()) {
                return new Builder()
                    .scheme(getScheme())
                    .encodedAuthority(getEncodedAuthority())
                    .encodedPath(getEncodedPath())
                    .encodedQuery(getEncodedQuery())
                    .encodedFragment(getEncodedFragment());
            }
            return new Builder()
                .scheme(getScheme())
                .encodedOpaquePart(getEncodedSchemeSpecificPart())
                .encodedFragment(getEncodedFragment());
        }

        @Override
        public String toString() {
            return uri;
        }
    }

    /**
     * Builds a Uri from parts. Setters without "encoded" in their name take decoded values
     * and escape them; the "encoded" variants take the value as is.
     */
    public static final class Builder {
        private String scheme;
        private String opaquePart;
        private String authority;
        private String path;
        private String query;
        private String fragment;

        public Builder() {}

        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder opaquePart(String opaquePart) {
            this.opaquePart = encode(opaquePart);
            return this;
        }

        public Builder encodedOpaquePart(String opaquePart) {
            this.opaquePart = opaquePart;
            return this;
        }

        public Builder authority(String authority) {
            this.opaquePart = null;
            this.authority = encode(authority, "@:[]");
            return this;
        }

        public Builder encodedAuthority(String authority) {
            this.opaquePart = null;
            this.authority = authority;
            return this;
        }

        public Builder path(String path) {
            this.opaquePart = null;
            this.path = encode(path, "/");
            return this;
        }

        public Builder encodedPath(String path) {
            this.opaquePart = null;
            this.path = path;
            return this;
        }

        public Builder appendPath(String newSegment) {
            return appendEncodedPath(encode(newSegment));
        }

        public Builder appendEncodedPath(String newSegment) {
            this.opaquePart = null;
            if (path == null || path.isEmpty()) {
                path = newSegment.startsWith("/") ? newSegment : "/" + newSegment;
            } else if (path.endsWith("/")) {
                path = path + (newSegment.startsWith("/") ? newSegment.substring(1) : newSegment);
            } else {
                path = path + (newSegment.startsWith("/") ? newSegment : "/" + newSegment);
            }
            return this;
        }

        public Builder query(String query) {
            this.opaquePart = null;
            this.query = encode(query, "=&");
            return this;
        }

        public Builder encodedQuery(String query) {
            this.opaquePart = null;
            this.query = query;
            return this;
        }

        public Builder appendQueryParameter(String key, String value) {
            this.opaquePart = null;
            String pair = encode(key, null) + "=" + encode(value, null);
            query = query == null ? pair : query + "&" + pair;
            return this;
        }

        public Builder clearQuery() {
            this.query = null;
            return this;
        }

        public Builder fragment(String fragment) {
            this.fragment = encode(fragment);
            return this;
        }

        public Builder encodedFragment(String fragment) {
            this.fragment = fragment;
            return this;
        }

        public Uri build() {
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append(':');
            if (opaquePart != null) {
                sb.append(opaquePart);
            } else {
                if (authority != null) sb.append("//").append(authority);
                if (path != null && !path.isEmpty()) {
                    if (authority != null && !path.startsWith("/")) sb.append('/');
                    sb.append(path);
                }
                if (query != null) sb.append('?').append(query);
            }
            if (fragment != null) sb.append('#').append(fragment);
            return new StringUri(sb.toString());
        }

        @Override
        public String toString() {
            return build().toString();
        }
    }
}
