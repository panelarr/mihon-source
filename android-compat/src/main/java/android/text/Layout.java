// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Laid-out text: lines with start/end offsets and vertical positions. Subclasses compute the
 * lines; drawing honours the alignment.
 */
public abstract class Layout {
    public static final int BREAK_STRATEGY_SIMPLE = 0;
    public static final int BREAK_STRATEGY_HIGH_QUALITY = 1;
    public static final int BREAK_STRATEGY_BALANCED = 2;
    public static final int HYPHENATION_FREQUENCY_NONE = 0;
    public static final int HYPHENATION_FREQUENCY_NORMAL = 1;
    public static final int HYPHENATION_FREQUENCY_FULL = 2;
    public static final int JUSTIFICATION_MODE_NONE = 0;
    public static final int JUSTIFICATION_MODE_INTER_WORD = 1;
    public static final int DIR_LEFT_TO_RIGHT = 1;
    public static final int DIR_RIGHT_TO_LEFT = -1;

    public enum Alignment { ALIGN_NORMAL, ALIGN_OPPOSITE, ALIGN_CENTER }

    private final CharSequence text;
    private final TextPaint paint;
    private final int width;
    private final Alignment alignment;
    private final float spacingMult;
    private final float spacingAdd;

    protected Layout(CharSequence text, TextPaint paint, int width, Alignment align, float spacingMult, float spacingAdd) {
        if (width < 0) throw new IllegalArgumentException("Layout: " + width + " < 0");
        this.text = text;
        this.paint = paint;
        this.width = width;
        this.alignment = align == null ? Alignment.ALIGN_NORMAL : align;
        this.spacingMult = spacingMult;
        this.spacingAdd = spacingAdd;
    }

    public final CharSequence getText() {
        return text;
    }

    public final TextPaint getPaint() {
        return paint;
    }

    public final int getWidth() {
        return width;
    }

    public final Alignment getAlignment() {
        return alignment;
    }

    public final float getSpacingMultiplier() {
        return spacingMult;
    }

    public final float getSpacingAdd() {
        return spacingAdd;
    }

    public abstract int getLineCount();

    public abstract int getLineTop(int line);

    public abstract int getLineStart(int line);

    public abstract int getLineDescent(int line);

    public final int getLineEnd(int line) {
        return line + 1 < getLineCount() ? getLineStart(line + 1) : text.length();
    }

    public final int getLineBottom(int line) {
        return getLineTop(line + 1);
    }

    public final int getLineBaseline(int line) {
        return getLineTop(line + 1) - getLineDescent(line);
    }

    public int getHeight() {
        return getLineTop(getLineCount());
    }

    /** The visible end of a line: without its trailing line break and spaces. */
    int visibleEnd(int line) {
        int start = getLineStart(line);
        int end = getLineEnd(line);
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return end;
    }

    public float getLineWidth(int line) {
        return paint.measureText(text.toString(), getLineStart(line), visibleEnd(line));
    }

    public float getLineMax(int line) {
        return getLineWidth(line);
    }

    public float getLineLeft(int line) {
        float free = width - getLineWidth(line);
        return switch (alignment) {
            case ALIGN_NORMAL -> 0f;
            case ALIGN_OPPOSITE -> free;
            case ALIGN_CENTER -> free / 2f;
        };
    }

    public float getLineRight(int line) {
        return getLineLeft(line) + getLineWidth(line);
    }

    public int getLineForVertical(int vertical) {
        int line = 0;
        while (line + 1 < getLineCount() && getLineTop(line + 1) <= vertical) line++;
        return line;
    }

    public final int getParagraphDirection(int line) {
        return DIR_LEFT_TO_RIGHT;
    }

    public static float getDesiredWidth(CharSequence source, TextPaint paint) {
        float max = 0;
        for (String line : source.toString().split("\n", -1)) max = Math.max(max, paint.measureText(line));
        return max;
    }

    public void draw(Canvas canvas) {
        Paint linePaint = new Paint(paint);
        linePaint.setTextAlign(Paint.Align.LEFT);
        String s = text.toString();
        for (int line = 0; line < getLineCount(); line++) {
            int start = getLineStart(line);
            int end = visibleEnd(line);
            if (end > start) canvas.drawText(s, start, end, getLineLeft(line), getLineBaseline(line), linePaint);
        }
    }
}
