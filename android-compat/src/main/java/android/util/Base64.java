// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

import java.nio.charset.StandardCharsets;

/** Android's Base64 helper, with its flags, on top of java.util.Base64. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private static final int LINE_LENGTH = 76;

    private Base64() {}

    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decode(input, 0, input.length, flags);
    }

    public static byte[] decode(byte[] input, int offset, int len, int flags) {
        // Android's decoder accepts both alphabets, skips whitespace and allows missing padding.
        StringBuilder clean = new StringBuilder(len);
        for (int i = offset; i < offset + len; i++) {
            char c = (char) (input[i] & 0xff);
            if (Character.isWhitespace(c)) continue;
            if (c == '-') c = '+';
            else if (c == '_') c = '/';
            clean.append(c);
        }
        while (clean.length() % 4 != 0) clean.append('=');
        try {
            return java.util.Base64.getDecoder().decode(clean.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base-64", e);
        }
    }

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        return new String(encode(input, offset, len, flags), StandardCharsets.US_ASCII);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encode(input, 0, input.length, flags);
    }

    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        byte[] slice = java.util.Arrays.copyOfRange(input, offset, offset + len);
        java.util.Base64.Encoder encoder = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) encoder = encoder.withoutPadding();
        String encoded = encoder.encodeToString(slice);
        if ((flags & NO_WRAP) == 0) {
            String newline = (flags & CRLF) != 0 ? "\r\n" : "\n";
            StringBuilder wrapped = new StringBuilder();
            for (int i = 0; i < encoded.length(); i += LINE_LENGTH) {
                wrapped.append(encoded, i, Math.min(encoded.length(), i + LINE_LENGTH)).append(newline);
            }
            encoded = wrapped.toString();
        }
        return encoded.getBytes(StandardCharsets.US_ASCII);
    }
}
