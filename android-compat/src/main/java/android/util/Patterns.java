// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

import java.util.regex.Pattern;

/** Common regular expressions. Written for this host; close to, not identical with, Android's. */
public class Patterns {
    private static final String LABEL = "[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?";

    public static final Pattern IP_ADDRESS = Pattern.compile(
        "\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b");

    public static final Pattern DOMAIN_NAME = Pattern.compile("(?:" + LABEL + "\\.)+\\p{L}{2,63}|" + IP_ADDRESS.pattern());

    public static final Pattern WEB_URL = Pattern.compile(
        "(?:(?i:https?|rtsp|ftp)://(?:[^\\s/@]+@)?)?(?:" + DOMAIN_NAME.pattern() + ")(?::\\d{1,5})?(?:[/?#][^\\s]*)?");

    public static final Pattern EMAIL_ADDRESS = Pattern.compile(
        "[A-Za-z0-9+._%-]{1,256}@[A-Za-z0-9][A-Za-z0-9-]{0,64}(?:\\.[A-Za-z0-9][A-Za-z0-9-]{0,25})+");

    public static final Pattern PHONE = Pattern.compile("(?:\\+\\d+[\\- .]*)?(?:\\(\\d+\\)[\\- .]*)?\\d[\\d\\- .]{3,}\\d");

    private Patterns() {}
}
