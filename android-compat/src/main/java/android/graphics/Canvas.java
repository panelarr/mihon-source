// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Draws into a {@link Bitmap} with Graphics2D. Transforms, clips and save/restore follow
 * Android. Bitmaps are drawn with nearest-neighbour sampling unless the paint asks for
 * filtering, so 1:1 copies (image unscrambling) are exact.
 */
public class Canvas {
    static {
        Headless.init();
    }

    public static final int ALL_SAVE_FLAG = 0x1F;

    private Bitmap bitmap;
    private Graphics2D g;
    private final Deque<State> saved = new ArrayDeque<>();
    private int density = Bitmap.DENSITY_NONE;

    private record State(AffineTransform transform, Shape clip) {}

    public Canvas() {}

    public Canvas(Bitmap bitmap) {
        setBitmap(bitmap);
    }

    public void setBitmap(Bitmap bitmap) {
        if (g != null) g.dispose();
        saved.clear();
        this.bitmap = bitmap;
        if (bitmap == null) {
            g = null;
            return;
        }
        g = bitmap.image().createGraphics();
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        density = bitmap.getDensity();
    }

    private Graphics2D g() {
        if (g == null) throw new IllegalStateException("Canvas has no bitmap");
        return g;
    }

    public int getWidth() {
        return bitmap == null ? 0 : bitmap.getWidth();
    }

    public int getHeight() {
        return bitmap == null ? 0 : bitmap.getHeight();
    }

    public boolean isOpaque() {
        return bitmap != null && !bitmap.hasAlpha();
    }

    public boolean isHardwareAccelerated() {
        return false;
    }

    public int getDensity() {
        return density;
    }

    public void setDensity(int density) {
        this.density = density;
    }

    // --- state -------------------------------------------------------------------------------

    public int save() {
        int count = getSaveCount();
        saved.push(new State(g().getTransform(), g().getClip()));
        return count;
    }

    public int save(int saveFlags) {
        return save();
    }

    public int saveLayer(RectF bounds, Paint paint) {
        return save();
    }

    public int saveLayer(float left, float top, float right, float bottom, Paint paint) {
        return save();
    }

    public int saveLayerAlpha(RectF bounds, int alpha) {
        return save();
    }

    public void restore() {
        if (saved.isEmpty()) throw new IllegalStateException("Underflow in restore - more restores than saves");
        State state = saved.pop();
        g().setTransform(state.transform());
        g().setClip(state.clip());
    }

    public int getSaveCount() {
        return saved.size() + 1;
    }

    public void restoreToCount(int saveCount) {
        if (saveCount < 1) throw new IllegalArgumentException("Underflow in restoreToCount - more restores than saves");
        while (getSaveCount() > saveCount) restore();
    }

    // --- transforms --------------------------------------------------------------------------

    public void translate(float dx, float dy) {
        g().translate(dx, dy);
    }

    public void scale(float sx, float sy) {
        g().scale(sx, sy);
    }

    public final void scale(float sx, float sy, float px, float py) {
        g().translate(px, py);
        g().scale(sx, sy);
        g().translate(-px, -py);
    }

    public void rotate(float degrees) {
        g().rotate(Math.toRadians(degrees));
    }

    public final void rotate(float degrees, float px, float py) {
        g().rotate(Math.toRadians(degrees), px, py);
    }

    public void skew(float sx, float sy) {
        g().shear(sx, sy);
    }

    public void concat(Matrix matrix) {
        if (matrix != null) g().transform(matrix.transform);
    }

    public void setMatrix(Matrix matrix) {
        g().setTransform(matrix == null ? new AffineTransform() : new AffineTransform(matrix.transform));
    }

    public void getMatrix(Matrix ctm) {
        ctm.transform.setTransform(g().getTransform());
    }

    public final Matrix getMatrix() {
        Matrix m = new Matrix();
        getMatrix(m);
        return m;
    }

    // --- clipping ----------------------------------------------------------------------------

    private boolean clip(Shape shape) {
        g().clip(shape);
        Shape clip = g().getClip();
        return clip != null && !clip.getBounds2D().isEmpty();
    }

    public boolean clipRect(float left, float top, float right, float bottom) {
        return clip(new Rectangle2D.Float(left, top, right - left, bottom - top));
    }

    public boolean clipRect(int left, int top, int right, int bottom) {
        return clip(new Rectangle2D.Float(left, top, right - left, bottom - top));
    }

    public boolean clipRect(Rect rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    public boolean clipRect(RectF rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    public boolean getClipBounds(Rect bounds) {
        Shape clip = g().getClip();
        Rectangle2D r = clip == null ? new Rectangle2D.Float(0, 0, getWidth(), getHeight()) : clip.getBounds2D();
        if (clip == null) {
            try {
                r = g().getTransform().createInverse().createTransformedShape(r).getBounds2D();
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                r = new Rectangle2D.Float();
            }
        }
        bounds.set((int) Math.floor(r.getMinX()), (int) Math.floor(r.getMinY()), (int) Math.ceil(r.getMaxX()), (int) Math.ceil(r.getMaxY()));
        return !bounds.isEmpty();
    }

    public final Rect getClipBounds() {
        Rect r = new Rect();
        getClipBounds(r);
        return r;
    }

    // --- bitmaps -----------------------------------------------------------------------------

    /** The source pixels, with the paint's color filter applied when there is one. */
    private static BufferedImage source(Bitmap bitmap, Paint paint, Rect src) {
        BufferedImage image = bitmap.image();
        if (src != null) {
            int x = Math.max(0, src.left);
            int y = Math.max(0, src.top);
            int w = Math.min(image.getWidth(), src.right) - x;
            int h = Math.min(image.getHeight(), src.bottom) - y;
            if (w <= 0 || h <= 0) return null;
            image = image.getSubimage(x, y, w, h);
        }
        ColorFilter filter = paint == null ? null : paint.getColorFilter();
        if (filter == null || filter.getClass() == ColorFilter.class) return image;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, row.length, 1, row, 0, row.length);
            for (int i = 0; i < row.length; i++) row[i] = filter.filter(row[i]);
            out.setRGB(0, y, row.length, 1, row, 0, row.length);
        }
        return out;
    }

    private void withPaint(Paint paint, Runnable draw) {
        Graphics2D g2 = g();
        Composite composite = g2.getComposite();
        Object interpolation = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        Object antialias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        boolean filter = paint != null && paint.isFilterBitmap();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter
            ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, paint != null && paint.isAntiAlias()
            ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        if (paint != null && paint.getAlpha() < 255) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, paint.getAlpha() / 255f));
        }
        try {
            draw.run();
        } finally {
            g2.setComposite(composite);
            if (interpolation != null) g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            if (antialias != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialias);
        }
    }

    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        BufferedImage image = source(bitmap, paint, null);
        withPaint(paint, () -> {
            if (left == (int) left && top == (int) top) g().drawImage(image, (int) left, (int) top, null);
            else g().drawImage(image, AffineTransform.getTranslateInstance(left, top), null);
        });
    }

    public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
        BufferedImage image = source(bitmap, paint, src);
        if (image == null || dst.isEmpty()) return;
        withPaint(paint, () -> g().drawImage(image, dst.left, dst.top, dst.right, dst.bottom,
            0, 0, image.getWidth(), image.getHeight(), null));
    }

    public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
        BufferedImage image = source(bitmap, paint, src);
        if (image == null || dst.isEmpty()) return;
        AffineTransform at = AffineTransform.getTranslateInstance(dst.left, dst.top);
        at.scale(dst.width() / image.getWidth(), dst.height() / image.getHeight());
        withPaint(paint, () -> g().drawImage(image, at, null));
    }

    public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
        BufferedImage image = source(bitmap, paint, null);
        AffineTransform at = matrix == null ? new AffineTransform() : new AffineTransform(matrix.transform);
        withPaint(paint, () -> g().drawImage(image, at, null));
    }

    public void drawBitmap(int[] colors, int offset, int stride, int x, int y, int width, int height, boolean hasAlpha, Paint paint) {
        Bitmap tmp = Bitmap.createBitmap(colors, offset, stride, width, height, Bitmap.Config.ARGB_8888);
        drawBitmap(tmp, x, y, paint);
    }

    // --- shapes ------------------------------------------------------------------------------

    private void fillDevice(int argb) {
        Graphics2D g2 = g();
        AffineTransform transform = g2.getTransform();
        g2.setTransform(new AffineTransform());
        g2.setColor(new java.awt.Color(argb, true));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setTransform(transform);
    }

    /** Fills the clip with {@code color}, blended over what is there (Android's SRC_OVER). */
    public void drawColor(int color) {
        fillDevice(color);
    }

    public void drawARGB(int a, int r, int g, int b) {
        drawColor(Color.argb(a, r, g, b));
    }

    public void drawRGB(int r, int g, int b) {
        drawColor(Color.rgb(r, g, b));
    }

    public void drawPaint(Paint paint) {
        fillDevice(filtered(paint));
    }

    private static int filtered(Paint paint) {
        ColorFilter filter = paint.getColorFilter();
        return filter == null ? paint.getColor() : filter.filter(paint.getColor());
    }

    private void drawShape(Shape shape, Paint paint) {
        Graphics2D g2 = g();
        Object antialias = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, paint.isAntiAlias()
            ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setColor(new java.awt.Color(filtered(paint), true));
        Paint.Style style = paint.getStyle();
        if (style == Paint.Style.FILL || style == Paint.Style.FILL_AND_STROKE) g2.fill(shape);
        if (style == Paint.Style.STROKE || style == Paint.Style.FILL_AND_STROKE) {
            g2.setStroke(paint.stroke());
            g2.draw(shape);
        }
        if (antialias != null) g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialias);
    }

    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        drawShape(new Rectangle2D.Float(left, top, right - left, bottom - top), paint);
    }

    public void drawRect(Rect r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRect(RectF r, Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint);
    }

    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
        drawShape(new RoundRectangle2D.Float(left, top, right - left, bottom - top, rx * 2, ry * 2), paint);
    }

    public void drawOval(RectF oval, Paint paint) {
        drawOval(oval.left, oval.top, oval.right, oval.bottom, paint);
    }

    public void drawOval(float left, float top, float right, float bottom, Paint paint) {
        drawShape(new Ellipse2D.Float(left, top, right - left, bottom - top), paint);
    }

    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        drawShape(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2), paint);
    }

    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        Paint stroke = new Paint(paint);
        stroke.setStyle(Paint.Style.STROKE);
        drawShape(new Line2D.Float(startX, startY, stopX, stopY), stroke);
    }

    public void drawLines(float[] pts, Paint paint) {
        for (int i = 0; i + 3 < pts.length; i += 4) drawLine(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], paint);
    }

    public void drawPoint(float x, float y, Paint paint) {
        float half = Math.max(paint.getStrokeWidth(), 1f) / 2;
        Paint fill = new Paint(paint);
        fill.setStyle(Paint.Style.FILL);
        drawShape(new Rectangle2D.Float(x - half, y - half, half * 2, half * 2), fill);
    }

    // --- text --------------------------------------------------------------------------------

    /** Draws text with its baseline at y; x is the left edge, center or right edge per the paint's align. */
    public void drawText(String text, float x, float y, Paint paint) {
        drawText(text, 0, text.length(), x, y, paint);
    }

    public void drawText(String text, int start, int end, float x, float y, Paint paint) {
        if (start >= end) return;
        String s = text.substring(start, end);
        GlyphVector glyphs = paint.font().createGlyphVector(paint.renderContext(), s);
        float width = (float) glyphs.getLogicalBounds().getWidth();
        float left = switch (paint.getTextAlign()) {
            case LEFT -> x;
            case CENTER -> x - width / 2;
            case RIGHT -> x - width;
        };
        Shape outline = glyphs.getOutline(left, y);
        drawShape(outline, paint);
        if (paint.isUnderlineText()) {
            float thickness = Math.max(1f, paint.getTextSize() / 18f);
            drawShape(new Rectangle2D.Float(left, y + thickness, width, thickness), filled(paint));
        }
        if (paint.isStrikeThruText()) {
            float thickness = Math.max(1f, paint.getTextSize() / 18f);
            drawShape(new Rectangle2D.Float(left, y - paint.getTextSize() * 0.3f, width, thickness), filled(paint));
        }
    }

    private static Paint filled(Paint paint) {
        Paint fill = new Paint(paint);
        fill.setStyle(Paint.Style.FILL);
        return fill;
    }

    public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
        drawText(text.toString(), start, end, x, y, paint);
    }

    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        drawText(new String(text, index, count), x, y, paint);
    }
}
