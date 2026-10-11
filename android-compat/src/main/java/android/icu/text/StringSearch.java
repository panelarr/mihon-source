// SPDX-License-Identifier: GPL-3.0-or-later

package android.icu.text;

import java.text.CharacterIterator;
import java.util.Locale;

/**
 * Finds a pattern in text using a collator, so "cafe" can match "Café" at primary strength.
 * A plain scan: at each start offset the shortest span the collator calls equal to the pattern
 * matches, extended over trailing combining marks.
 */
public final class StringSearch extends SearchIterator {
    private String pattern;
    private String text = "";
    private final RuleBasedCollator collator;
    private boolean overlapping;
    private int position;
    private int matchStart = DONE;
    private int matchLength;

    public StringSearch(String pattern, CharacterIterator target, RuleBasedCollator collator) {
        this.pattern = pattern;
        this.collator = collator;
        setTarget(target);
    }

    public StringSearch(String pattern, CharacterIterator target, Locale locale) {
        this(pattern, target, RuleBasedCollator.forLocale(locale));
    }

    public StringSearch(String pattern, String target) {
        this(pattern, new java.text.StringCharacterIterator(target), RuleBasedCollator.forLocale(Locale.getDefault()));
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
        reset();
    }

    public RuleBasedCollator getCollator() {
        return collator;
    }

    @Override
    public void setTarget(CharacterIterator target) {
        StringBuilder sb = new StringBuilder();
        for (char c = target.first(); c != CharacterIterator.DONE; c = target.next()) sb.append(c);
        text = sb.toString();
        reset();
    }

    @Override
    public void reset() {
        position = 0;
        matchStart = DONE;
        matchLength = 0;
    }

    @Override
    public void setOverlapping(boolean allowOverlap) {
        overlapping = allowOverlap;
    }

    @Override
    public boolean isOverlapping() {
        return overlapping;
    }

    @Override
    public int first() {
        reset();
        return next();
    }

    public int following(int offset) {
        reset();
        position = offset;
        return next();
    }

    @Override
    public int next() {
        if (pattern == null || pattern.isEmpty()) return DONE;
        int maxSpan = pattern.length() * 3 + 4;
        for (int start = position; start < text.length(); start++) {
            if (Character.getType(text.charAt(start)) == Character.NON_SPACING_MARK && start > 0) continue;
            // A match never starts with a character the collator ignores (spaces at primary strength).
            if (collator.compare(text.substring(start, start + 1), "") == 0) continue;
            int limit = Math.min(text.length(), start + maxSpan);
            for (int end = start + 1; end <= limit; end++) {
                if (collator.compare(text.substring(start, end), pattern) == 0) {
                    while (end < text.length() && Character.getType(text.charAt(end)) == Character.NON_SPACING_MARK) end++;
                    matchStart = start;
                    matchLength = end - start;
                    position = overlapping ? start + 1 : end;
                    return matchStart;
                }
            }
        }
        matchStart = DONE;
        matchLength = 0;
        position = text.length();
        return DONE;
    }

    @Override
    public int getMatchStart() {
        return matchStart;
    }

    @Override
    public int getMatchLength() {
        return matchLength;
    }

    @Override
    public String getMatchedText() {
        return matchStart == DONE ? null : text.substring(matchStart, matchStart + matchLength);
    }
}
