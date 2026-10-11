// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/** Span bookkeeping shared by the host's Spanned implementations. Spans don't move on edits. */
final class SpanStore {
    private record Span(Object what, int start, int end, int flags) {}

    private final List<Span> spans = new ArrayList<>();

    void copyFrom(CharSequence source, int start, int end) {
        if (!(source instanceof Spanned spanned)) return;
        for (Object span : spanned.getSpans(start, end, Object.class)) {
            int s = Math.max(start, spanned.getSpanStart(span)) - start;
            int e = Math.min(end, spanned.getSpanEnd(span)) - start;
            spans.add(new Span(span, s, e, spanned.getSpanFlags(span)));
        }
    }

    void set(Object what, int start, int end, int flags, int length) {
        if (start < 0 || end > length || start > end) {
            throw new IndexOutOfBoundsException("span " + start + ".." + end + " outside 0.." + length);
        }
        remove(what);
        spans.add(new Span(what, start, end, flags));
    }

    void remove(Object what) {
        spans.removeIf(span -> span.what == what);
    }

    void clear() {
        spans.clear();
    }

    @SuppressWarnings("unchecked")
    <T> T[] get(int start, int end, Class<T> type) {
        Class<?> kind = type == null ? Object.class : type;
        List<Object> found = new ArrayList<>();
        for (Span span : spans) {
            boolean overlaps = span.start <= end && span.end >= start;
            if (overlaps && kind.isInstance(span.what)) found.add(span.what);
        }
        return found.toArray((T[]) Array.newInstance(kind, found.size()));
    }

    private Span find(Object what) {
        for (Span span : spans) if (span.what == what) return span;
        return null;
    }

    int start(Object what) {
        Span span = find(what);
        return span == null ? -1 : span.start;
    }

    int end(Object what) {
        Span span = find(what);
        return span == null ? -1 : span.end;
    }

    int flags(Object what) {
        Span span = find(what);
        return span == null ? 0 : span.flags;
    }

    int nextTransition(int start, int limit, Class<?> type) {
        int next = limit;
        for (Span span : spans) {
            if (type != null && !type.isInstance(span.what)) continue;
            if (span.start > start && span.start < next) next = span.start;
            if (span.end > start && span.end < next) next = span.end;
        }
        return next;
    }
}
