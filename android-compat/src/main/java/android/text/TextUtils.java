// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

import java.util.Iterator;

/** The commonly used helpers of Android's TextUtils. */
public final class TextUtils {
    private TextUtils() {}

    public enum TruncateAt { START, MIDDLE, END, MARQUEE }

    public static boolean isGraphic(CharSequence str) {
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isWhitespace(str.charAt(i)) && !Character.isISOControl(str.charAt(i))) return true;
        }
        return false;
    }

    public static CharSequence concat(CharSequence... text) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence piece : text) sb.append(piece);
        return sb.toString();
    }

    public static String substring(CharSequence source, int start, int end) {
        return source.subSequence(start, end).toString();
    }

    public static int getTrimmedLength(CharSequence s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) <= ' ') start++;
        while (end > start && s.charAt(end - 1) <= ' ') end--;
        return end - start;
    }

    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }

    public static boolean isDigitsOnly(CharSequence str) {
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) return false;
        }
        return true;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().contentEquals(b);
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = tokens.iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) sb.append(delimiter);
        }
        return sb.toString();
    }

    public static String[] split(String text, String expression) {
        if (text.isEmpty()) return new String[0];
        return text.split(expression, -1);
    }

    public static String htmlEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '\'' -> sb.append("&#39;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
