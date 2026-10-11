// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

/** Editable text with spans. Spans keep their offsets when the text is edited. */
public class SpannableStringBuilder implements Editable {
    private final StringBuilder text;
    private final SpanStore spans = new SpanStore();
    private InputFilter[] filters = new InputFilter[0];

    public SpannableStringBuilder() {
        this("");
    }

    public SpannableStringBuilder(CharSequence text) {
        this(text, 0, text.length());
    }

    public SpannableStringBuilder(CharSequence text, int start, int end) {
        this.text = new StringBuilder(text.subSequence(start, end));
        spans.copyFrom(text, start, end);
    }

    public static SpannableStringBuilder valueOf(CharSequence source) {
        return source instanceof SpannableStringBuilder s ? s : new SpannableStringBuilder(source);
    }

    @Override
    public SpannableStringBuilder replace(int st, int en, CharSequence source, int start, int end) {
        text.replace(st, en, source.subSequence(start, end).toString());
        return this;
    }

    @Override
    public SpannableStringBuilder replace(int st, int en, CharSequence source) {
        return replace(st, en, source, 0, source.length());
    }

    @Override
    public SpannableStringBuilder insert(int where, CharSequence source, int start, int end) {
        return replace(where, where, source, start, end);
    }

    @Override
    public SpannableStringBuilder insert(int where, CharSequence source) {
        return replace(where, where, source, 0, source.length());
    }

    @Override
    public SpannableStringBuilder delete(int st, int en) {
        text.delete(st, en);
        return this;
    }

    @Override
    public SpannableStringBuilder append(CharSequence source) {
        CharSequence s = source == null ? "null" : source;
        return replace(text.length(), text.length(), s, 0, s.length());
    }

    @Override
    public SpannableStringBuilder append(CharSequence source, int start, int end) {
        return replace(text.length(), text.length(), source, start, end);
    }

    @Override
    public SpannableStringBuilder append(char c) {
        text.append(c);
        return this;
    }

    public SpannableStringBuilder append(CharSequence source, Object what, int flags) {
        int start = text.length();
        append(source);
        setSpan(what, start, text.length(), flags);
        return this;
    }

    @Override
    public void clear() {
        text.setLength(0);
    }

    @Override
    public void clearSpans() {
        spans.clear();
    }

    @Override
    public void setFilters(InputFilter[] filters) {
        this.filters = filters.clone();
    }

    @Override
    public InputFilter[] getFilters() {
        return filters.clone();
    }

    @Override
    public void setSpan(Object what, int start, int end, int flags) {
        spans.set(what, start, end, flags, text.length());
    }

    @Override
    public void removeSpan(Object what) {
        spans.remove(what);
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
    public void getChars(int start, int end, char[] dest, int destoff) {
        text.getChars(start, end, dest, destoff);
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
        return new SpannableStringBuilder(this, start, end);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Spanned && o.toString().contentEquals(text);
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
