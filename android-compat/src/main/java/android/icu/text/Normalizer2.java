// SPDX-License-Identifier: GPL-3.0-or-later

package android.icu.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ICU's Normalizer2 for the standard forms, on java.text.Normalizer. NFKC_Casefold is NFKC
 * with full case folding and default-ignorable characters removed (an approximation of ICU's).
 */
public abstract class Normalizer2 {
    public enum Mode { COMPOSE, DECOMPOSE, FCD, COMPOSE_CONTIGUOUS }

    private static final Pattern IGNORABLE = Pattern.compile(
        "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180F\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F"
            + "\\u3164\\uFE00-\\uFE0F\\uFEFF\\uFFA0\\uFFF0-\\uFFF8]");

    protected Normalizer2() {}

    private static Normalizer2 of(Normalizer.Form form, boolean casefold) {
        return new Normalizer2() {
            @Override
            public String normalize(CharSequence src) {
                String s = Normalizer.normalize(src, form);
                if (!casefold) return s;
                s = IGNORABLE.matcher(s).replaceAll("");
                s = s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
                return Normalizer.normalize(s, form);
            }
        };
    }

    public static Normalizer2 getNFCInstance() {
        return of(Normalizer.Form.NFC, false);
    }

    public static Normalizer2 getNFDInstance() {
        return of(Normalizer.Form.NFD, false);
    }

    public static Normalizer2 getNFKCInstance() {
        return of(Normalizer.Form.NFKC, false);
    }

    public static Normalizer2 getNFKDInstance() {
        return of(Normalizer.Form.NFKD, false);
    }

    public static Normalizer2 getNFKCCasefoldInstance() {
        return of(Normalizer.Form.NFKC, true);
    }

    public abstract String normalize(CharSequence src);

    public StringBuilder normalize(CharSequence src, StringBuilder dest) {
        dest.setLength(0);
        return dest.append(normalize(src));
    }

    public boolean isNormalized(CharSequence s) {
        return normalize(s).contentEquals(s);
    }

    public StringBuilder normalizeSecondAndAppend(StringBuilder first, CharSequence second) {
        String joined = normalize(first.toString() + second);
        first.setLength(0);
        return first.append(joined);
    }

    public StringBuilder append(StringBuilder first, CharSequence second) {
        return normalizeSecondAndAppend(first, second);
    }
}
