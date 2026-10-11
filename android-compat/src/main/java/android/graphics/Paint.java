// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.Rectangle2D;

/** How to draw: color, style, stroke and text settings. Text is measured with java.awt fonts. */
public class Paint {
    static {
        Headless.init();
    }

    public static final int ANTI_ALIAS_FLAG = 0x01;
    public static final int FILTER_BITMAP_FLAG = 0x02;
    public static final int DITHER_FLAG = 0x04;
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    public static final int LINEAR_TEXT_FLAG = 0x40;
    public static final int SUBPIXEL_TEXT_FLAG = 0x80;

    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    public enum Join { MITER, ROUND, BEVEL }

    public enum Cap { BUTT, ROUND, SQUARE }

    public enum Align { LEFT, CENTER, RIGHT }

    public static class FontMetrics {
        public float top;
        public float ascent;
        public float descent;
        public float bottom;
        public float leading;

        public FontMetrics() {}
    }

    public static class FontMetricsInt {
        public int top;
        public int ascent;
        public int descent;
        public int bottom;
        public int leading;

        public FontMetricsInt() {}
    }

    private int flags;
    private int color;
    private Style style;
    private float strokeWidth;
    private float strokeMiter;
    private Join strokeJoin;
    private Cap strokeCap;
    private float textSize;
    private float textScaleX;
    private float textSkewX;
    private float letterSpacing;
    private Typeface typeface;
    private Align textAlign;
    private ColorFilter colorFilter;

    public Paint() {
        reset();
        flags = DITHER_FLAG;
    }

    public Paint(int flags) {
        reset();
        this.flags = flags;
    }

    public Paint(Paint paint) {
        set(paint);
    }

    public void reset() {
        flags = DITHER_FLAG;
        color = 0xFF000000;
        style = Style.FILL;
        strokeWidth = 0f;
        strokeMiter = 4f;
        strokeJoin = Join.MITER;
        strokeCap = Cap.BUTT;
        textSize = 12f;
        textScaleX = 1f;
        textSkewX = 0f;
        letterSpacing = 0f;
        typeface = null;
        textAlign = Align.LEFT;
        colorFilter = null;
    }

    public void set(Paint src) {
        flags = src.flags;
        color = src.color;
        style = src.style;
        strokeWidth = src.strokeWidth;
        strokeMiter = src.strokeMiter;
        strokeJoin = src.strokeJoin;
        strokeCap = src.strokeCap;
        textSize = src.textSize;
        textScaleX = src.textScaleX;
        textSkewX = src.textSkewX;
        letterSpacing = src.letterSpacing;
        typeface = src.typeface;
        textAlign = src.textAlign;
        colorFilter = src.colorFilter;
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    private void flag(int flag, boolean on) {
        flags = on ? flags | flag : flags & ~flag;
    }

    public final boolean isAntiAlias() {
        return (flags & ANTI_ALIAS_FLAG) != 0;
    }

    public void setAntiAlias(boolean aa) {
        flag(ANTI_ALIAS_FLAG, aa);
    }

    public final boolean isFilterBitmap() {
        return (flags & FILTER_BITMAP_FLAG) != 0;
    }

    public void setFilterBitmap(boolean filter) {
        flag(FILTER_BITMAP_FLAG, filter);
    }

    public final boolean isDither() {
        return (flags & DITHER_FLAG) != 0;
    }

    public void setDither(boolean dither) {
        flag(DITHER_FLAG, dither);
    }

    public final boolean isFakeBoldText() {
        return (flags & FAKE_BOLD_TEXT_FLAG) != 0;
    }

    public void setFakeBoldText(boolean fakeBoldText) {
        flag(FAKE_BOLD_TEXT_FLAG, fakeBoldText);
    }

    public final boolean isUnderlineText() {
        return (flags & UNDERLINE_TEXT_FLAG) != 0;
    }

    public void setUnderlineText(boolean underlineText) {
        flag(UNDERLINE_TEXT_FLAG, underlineText);
    }

    public final boolean isStrikeThruText() {
        return (flags & STRIKE_THRU_TEXT_FLAG) != 0;
    }

    public void setStrikeThruText(boolean strikeThruText) {
        flag(STRIKE_THRU_TEXT_FLAG, strikeThruText);
    }

    public void setSubpixelText(boolean subpixelText) {
        flag(SUBPIXEL_TEXT_FLAG, subpixelText);
    }

    public void setLinearText(boolean linearText) {
        flag(LINEAR_TEXT_FLAG, linearText);
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int getAlpha() {
        return color >>> 24;
    }

    public void setAlpha(int a) {
        color = (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    public void setARGB(int a, int r, int g, int b) {
        setColor(Color.argb(a, r, g, b));
    }

    public Style getStyle() {
        return style;
    }

    public void setStyle(Style style) {
        this.style = style;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(float width) {
        this.strokeWidth = width;
    }

    public float getStrokeMiter() {
        return strokeMiter;
    }

    public void setStrokeMiter(float miter) {
        this.strokeMiter = miter;
    }

    public Join getStrokeJoin() {
        return strokeJoin;
    }

    public void setStrokeJoin(Join join) {
        this.strokeJoin = join;
    }

    public Cap getStrokeCap() {
        return strokeCap;
    }

    public void setStrokeCap(Cap cap) {
        this.strokeCap = cap;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setTextSize(float textSize) {
        this.textSize = textSize;
    }

    public float getTextScaleX() {
        return textScaleX;
    }

    public void setTextScaleX(float scaleX) {
        this.textScaleX = scaleX;
    }

    public float getTextSkewX() {
        return textSkewX;
    }

    public void setTextSkewX(float skewX) {
        this.textSkewX = skewX;
    }

    public float getLetterSpacing() {
        return letterSpacing;
    }

    public void setLetterSpacing(float letterSpacing) {
        this.letterSpacing = letterSpacing;
    }

    public Typeface getTypeface() {
        return typeface;
    }

    public Typeface setTypeface(Typeface typeface) {
        this.typeface = typeface;
        return typeface;
    }

    public Align getTextAlign() {
        return textAlign;
    }

    public void setTextAlign(Align align) {
        this.textAlign = align;
    }

    public ColorFilter getColorFilter() {
        return colorFilter;
    }

    public ColorFilter setColorFilter(ColorFilter filter) {
        this.colorFilter = filter;
        return filter;
    }

    public void setShadowLayer(float radius, float dx, float dy, int shadowColor) {}

    public void clearShadowLayer() {}

    // --- text measurement ----------------------------------------------------------------

    Font font() {
        Font base = (typeface == null ? Typeface.DEFAULT : typeface).font;
        if (isFakeBoldText()) base = base.deriveFont(base.getStyle() | Font.BOLD);
        Font sized = base.deriveFont(textSize);
        if (textScaleX != 1f || textSkewX != 0f) {
            sized = sized.deriveFont(new java.awt.geom.AffineTransform(textScaleX, 0, textSkewX, 1, 0, 0));
        }
        return sized;
    }

    FontRenderContext renderContext() {
        return new FontRenderContext(null, isAntiAlias(), true);
    }

    public float measureText(String text) {
        return measureText(text, 0, text.length());
    }

    public float measureText(String text, int start, int end) {
        if (start == end) return 0f;
        return (float) font().getStringBounds(text, start, end, renderContext()).getWidth();
    }

    public float measureText(CharSequence text, int start, int end) {
        return measureText(text.toString(), start, end);
    }

    public float measureText(char[] text, int index, int count) {
        return measureText(new String(text, index, count));
    }

    public void getTextBounds(String text, int start, int end, Rect bounds) {
        if (start == end) {
            bounds.setEmpty();
            return;
        }
        Rectangle2D r = font().createGlyphVector(renderContext(), text.substring(start, end)).getVisualBounds();
        bounds.set((int) Math.floor(r.getMinX()), (int) Math.floor(r.getMinY()),
            (int) Math.ceil(r.getMaxX()), (int) Math.ceil(r.getMaxY()));
    }

    public void getTextBounds(CharSequence text, int start, int end, Rect bounds) {
        getTextBounds(text.toString(), start, end, bounds);
    }

    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        getTextBounds(new String(text, index, count), 0, count, bounds);
    }

    public float getFontMetrics(FontMetrics metrics) {
        Font font = font();
        FontRenderContext frc = renderContext();
        LineMetrics line = font.getLineMetrics("Xg", frc);
        Rectangle2D max = font.getMaxCharBounds(frc);
        float ascent = -line.getAscent();
        float descent = line.getDescent();
        if (metrics != null) {
            metrics.ascent = ascent;
            metrics.descent = descent;
            metrics.leading = line.getLeading();
            metrics.top = Math.min(ascent, (float) max.getMinY());
            metrics.bottom = Math.max(descent, (float) max.getMaxY());
        }
        return descent - ascent + line.getLeading();
    }

    public FontMetrics getFontMetrics() {
        FontMetrics metrics = new FontMetrics();
        getFontMetrics(metrics);
        return metrics;
    }

    public int getFontMetricsInt(FontMetricsInt metrics) {
        FontMetrics f = getFontMetrics();
        if (metrics != null) {
            metrics.top = (int) Math.floor(f.top);
            metrics.ascent = Math.round(f.ascent);
            metrics.descent = Math.round(f.descent);
            metrics.bottom = (int) Math.ceil(f.bottom);
            metrics.leading = Math.round(f.leading);
        }
        return Math.round(f.descent - f.ascent + f.leading);
    }

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt metrics = new FontMetricsInt();
        getFontMetricsInt(metrics);
        return metrics;
    }

    public float getFontSpacing() {
        return getFontMetrics(null);
    }

    public float ascent() {
        return getFontMetrics().ascent;
    }

    public float descent() {
        return getFontMetrics().descent;
    }

    public int breakText(String text, boolean measureForwards, float maxWidth, float[] measuredWidth) {
        int count = 0;
        float width = 0;
        int length = text.length();
        while (count < length) {
            int at = measureForwards ? count : length - count - 1;
            float next = measureText(measureForwards ? text.substring(0, at + 1) : text.substring(at));
            if (next > maxWidth) break;
            width = next;
            count++;
        }
        if (measuredWidth != null && measuredWidth.length > 0) measuredWidth[0] = width;
        return count;
    }

    BasicStroke stroke() {
        int cap = switch (strokeCap) {
            case BUTT -> BasicStroke.CAP_BUTT;
            case ROUND -> BasicStroke.CAP_ROUND;
            case SQUARE -> BasicStroke.CAP_SQUARE;
        };
        int join = switch (strokeJoin) {
            case MITER -> BasicStroke.JOIN_MITER;
            case ROUND -> BasicStroke.JOIN_ROUND;
            case BEVEL -> BasicStroke.JOIN_BEVEL;
        };
        // Width 0 is Android's "hairline": one pixel wide whatever the transform.
        return new BasicStroke(Math.max(strokeWidth, 1f), cap, join, Math.max(1f, strokeMiter));
    }
}
