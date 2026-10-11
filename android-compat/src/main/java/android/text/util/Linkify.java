// SPDX-License-Identifier: GPL-3.0-or-later

package android.text.util;

import android.text.Spannable;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.util.Patterns;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds web URLs, email addresses and phone numbers in text and marks them with URLSpans. */
public class Linkify {
    public static final int WEB_URLS = 0x01;
    public static final int EMAIL_ADDRESSES = 0x02;
    public static final int PHONE_NUMBERS = 0x04;
    public static final int MAP_ADDRESSES = 0x08;
    public static final int ALL = WEB_URLS | EMAIL_ADDRESSES | PHONE_NUMBERS | MAP_ADDRESSES;

    public Linkify() {}

    public static boolean addLinks(Spannable text, int mask) {
        boolean added = false;
        if ((mask & EMAIL_ADDRESSES) != 0) added |= add(text, Patterns.EMAIL_ADDRESS, "mailto:");
        if ((mask & WEB_URLS) != 0) added |= add(text, Patterns.WEB_URL, "http://");
        if ((mask & PHONE_NUMBERS) != 0) added |= add(text, Patterns.PHONE, "tel:");
        return added;
    }

    private static boolean add(Spannable text, Pattern pattern, String scheme) {
        boolean added = false;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (text.getSpans(m.start(), m.end(), URLSpan.class).length > 0) continue;
            String match = m.group();
            String url = match.contains(":") && !scheme.equals("tel:") ? match : scheme + match;
            text.setSpan(new URLSpan(url), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            added = true;
        }
        return added;
    }
}
