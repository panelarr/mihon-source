// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

import android.graphics.Paint;
import java.util.ArrayList;
import java.util.List;

/**
 * Text broken into lines that fit a width: greedy word wrapping at spaces, with words longer
 * than a line split by character. No hyphenation or bidi.
 */
public class StaticLayout extends Layout {
    private final int[] lineStarts;
    private final int[] lineTops;
    private final int descent;

    public StaticLayout(CharSequence source, TextPaint paint, int width, Alignment align, float spacingmult, float spacingadd,
        boolean includepad) {
        this(source, 0, source.length(), paint, width, align, spacingmult, spacingadd, includepad, Integer.MAX_VALUE);
    }

    public StaticLayout(CharSequence source, int bufstart, int bufend, TextPaint paint, int outerwidth, Alignment align,
        float spacingmult, float spacingadd, boolean includepad) {
        this(source, bufstart, bufend, paint, outerwidth, align, spacingmult, spacingadd, includepad, Integer.MAX_VALUE);
    }

    private StaticLayout(CharSequence source, int start, int end, TextPaint paint, int width, Alignment align,
        float spacingMult, float spacingAdd, boolean includePad, int maxLines) {
        super(source.subSequence(start, end), paint, width, align, spacingMult, spacingAdd);
        String text = getText().toString();
        List<Integer> starts = breakLines(text, paint, width);
        if (starts.size() > maxLines) starts = starts.subList(0, Math.max(1, maxLines));
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int ascent = includePad ? fm.top : fm.ascent;
        descent = includePad ? fm.bottom : fm.descent;
        int lineHeight = Math.round((descent - ascent) * spacingMult + spacingAdd);
        lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        lineTops = new int[lineStarts.length + 1];
        for (int i = 1; i <= lineStarts.length; i++) lineTops[i] = lineTops[i - 1] + Math.max(1, lineHeight);
    }

    private static List<Integer> breakLines(String text, Paint paint, int width) {
        List<Integer> starts = new ArrayList<>();
        int lineStart = 0;
        starts.add(0);
        while (lineStart < text.length()) {
            int newline = text.indexOf('\n', lineStart);
            int paragraphEnd = newline < 0 ? text.length() : newline;
            int next = fit(text, lineStart, paragraphEnd, paint, width);
            if (next >= paragraphEnd) next = newline < 0 ? text.length() : newline + 1;
            if (next >= text.length() && (newline < 0 || newline + 1 != text.length())) break;
            lineStart = next;
            starts.add(lineStart);
        }
        return starts;
    }

    /** The start of the next line when the current one starts at {@code start}. */
    private static int fit(String text, int start, int end, Paint paint, int width) {
        if (paint.measureText(text, start, end) <= width) return end;
        int lastBreak = -1;
        for (int i = start + 1; i <= end; i++) {
            if (i < end && text.charAt(i) == ' ') {
                if (paint.measureText(text, start, i) > width) break;
                lastBreak = i;
            }
        }
        if (lastBreak > start) {
            int next = lastBreak;
            while (next < end && text.charAt(next) == ' ') next++;
            return next;
        }
        int i = start + 1;
        while (i < end && paint.measureText(text, start, i + 1) <= width) i++;
        return i;
    }

    @Override
    public int getLineCount() {
        return lineStarts.length;
    }

    @Override
    public int getLineTop(int line) {
        return lineTops[line];
    }

    @Override
    public int getLineStart(int line) {
        return line >= lineStarts.length ? getText().length() : lineStarts[line];
    }

    @Override
    public int getLineDescent(int line) {
        return descent;
    }

    public static final class Builder {
        private final CharSequence source;
        private final int start;
        private final int end;
        private final TextPaint paint;
        private final int width;
        private Alignment alignment = Alignment.ALIGN_NORMAL;
        private float spacingAdd;
        private float spacingMult = 1f;
        private boolean includePad = true;
        private int maxLines = Integer.MAX_VALUE;

        private Builder(CharSequence source, int start, int end, TextPaint paint, int width) {
            this.source = source;
            this.start = start;
            this.end = end;
            this.paint = paint;
            this.width = width;
        }

        public static Builder obtain(CharSequence source, int start, int end, TextPaint paint, int width) {
            return new Builder(source, start, end, paint, width);
        }

        public Builder setAlignment(Alignment alignment) {
            this.alignment = alignment;
            return this;
        }

        public Builder setLineSpacing(float spacingAdd, float spacingMult) {
            this.spacingAdd = spacingAdd;
            this.spacingMult = spacingMult;
            return this;
        }

        public Builder setIncludePad(boolean includePad) {
            this.includePad = includePad;
            return this;
        }

        public Builder setMaxLines(int maxLines) {
            this.maxLines = maxLines;
            return this;
        }

        public Builder setBreakStrategy(int breakStrategy) {
            return this;
        }

        public Builder setHyphenationFrequency(int hyphenationFrequency) {
            return this;
        }

        public Builder setJustificationMode(int justificationMode) {
            return this;
        }

        public Builder setEllipsize(TextUtils.TruncateAt ellipsize) {
            return this;
        }

        public Builder setEllipsizedWidth(int ellipsizedWidth) {
            return this;
        }

        public Builder setUseLineSpacingFromFallbacks(boolean useLineSpacingFromFallbacks) {
            return this;
        }

        public Builder setIndents(int[] leftIndents, int[] rightIndents) {
            return this;
        }

        public StaticLayout build() {
            return new StaticLayout(source, start, end, paint, width, alignment, spacingMult, spacingAdd, includePad, maxLines);
        }
    }
}
