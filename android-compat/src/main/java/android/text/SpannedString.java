// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

/** Immutable text with spans. */
public final class SpannedString implements Spanned, GetChars {
    private final String text;
    private final SpanStore spans = new SpanStore();

    public SpannedString(CharSequence source) {
        this(source, 0, source.length());
    }

    SpannedString(CharSequence source, int start, int end) {
        text = source.subSequence(start, end).toString();
        spans.copyFrom(source, start, end);
    }

    public static SpannedString valueOf(CharSequence source) {
        return source instanceof SpannedString s ? s : new SpannedString(source);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public char charAt(int index) {
        return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new SpannedString(this, start, end);
    }

    @Override
    public void getChars(int start, int end, char[] dest, int destoff) {
        text.getChars(start, end, dest, destoff);
    }

    @Override
    public <T> T[] getSpans(int start, int end, Class<T> type) {
        return spans.get(start, end, type);
    }

    @Override
    public int getSpanStart(Object tag) {
        return spans.start(tag);
    }

    @Override
    public int getSpanEnd(Object tag) {
        return spans.end(tag);
    }

    @Override
    public int getSpanFlags(Object tag) {
        return spans.flags(tag);
    }

    @Override
    public int nextSpanTransition(int start, int limit, Class type) {
        return spans.nextTransition(start, limit, type);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Spanned && o.toString().equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
