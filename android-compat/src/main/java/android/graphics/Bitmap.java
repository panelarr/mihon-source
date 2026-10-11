// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import android.util.DisplayMetrics;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * A bitmap backed by an ARGB {@link BufferedImage}. Every {@link Config} is stored as 32-bit
 * ARGB; the config is only remembered. Mutability is tracked but not enforced, so drawing into
 * a decoded (immutable on Android) bitmap works here.
 */
public final class Bitmap implements android.os.Parcelable {
    static {
        Headless.init();
    }

    public static final int DENSITY_NONE = 0;

    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE, RGBA_1010102 }

    /** Output formats. WEBP variants are written as JPEG or PNG; see {@link #compress}. */
    public enum CompressFormat { JPEG, PNG, WEBP, WEBP_LOSSY, WEBP_LOSSLESS }

    private BufferedImage image;
    private final int width;
    private final int height;
    private Config config;
    private boolean mutable;
    private boolean hasAlpha = true;
    private int density = DisplayMetrics.DENSITY_DEFAULT;
    private int generationId;

    Bitmap(BufferedImage image, Config config, boolean mutable) {
        this.image = image;
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.config = config == null ? Config.ARGB_8888 : config;
        this.mutable = mutable;
    }

    BufferedImage image() {
        if (image == null) throw new IllegalStateException("Can't use a recycled bitmap");
        generationId++;
        return image;
    }

    private BufferedImage readImage(String what) {
        if (image == null) throw new IllegalStateException("Can't call " + what + " on a recycled bitmap");
        return image;
    }

    // --- factories ---------------------------------------------------------------------------

    public static Bitmap createBitmap(int width, int height, Config config) {
        return createBitmap(width, height, config, true);
    }

    public static Bitmap createBitmap(int width, int height, Config config, boolean hasAlpha) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("width and height must be > 0");
        Bitmap bitmap = new Bitmap(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), config, true);
        bitmap.hasAlpha = hasAlpha;
        if (!hasAlpha) bitmap.eraseColor(Color.BLACK);
        return bitmap;
    }

    public static Bitmap createBitmap(DisplayMetrics display, int width, int height, Config config) {
        return createBitmap(width, height, config);
    }

    public static Bitmap createBitmap(Bitmap src) {
        return createBitmap(src, 0, 0, src.getWidth(), src.getHeight());
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        return createBitmap(source, x, y, width, height, null, false);
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height, Matrix m, boolean filter) {
        if (x < 0) throw new IllegalArgumentException("x must be >= 0");
        if (y < 0) throw new IllegalArgumentException("y must be >= 0");
        if (width <= 0) throw new IllegalArgumentException("width must be > 0");
        if (height <= 0) throw new IllegalArgumentException("height must be > 0");
        if (x + width > source.getWidth()) throw new IllegalArgumentException("x + width must be <= bitmap.width()");
        if (y + height > source.getHeight()) throw new IllegalArgumentException("y + height must be <= bitmap.height()");
        BufferedImage src = source.readImage("createBitmap()");
        if (m == null || m.isIdentity()) {
            BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] row = new int[width];
            for (int r = 0; r < height; r++) {
                src.getRGB(x, y + r, width, 1, row, 0, width);
                copy.setRGB(0, r, width, 1, row, 0, width);
            }
            return new Bitmap(copy, source.config, false);
        }
        RectF bounds = new RectF(0, 0, width, height);
        m.mapRect(bounds);
        int w = Math.max(1, Math.round(bounds.width()));
        int h = Math.max(1, Math.round(bounds.height()));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter
            ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.translate(-bounds.left, -bounds.top);
        g.transform(m.transform);
        g.drawImage(src.getSubimage(x, y, width, height), 0, 0, null);
        g.dispose();
        return new Bitmap(out, source.config, false);
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    public static Bitmap createBitmap(int[] colors, int offset, int stride, int width, int height, Config config) {
        Bitmap bitmap = createBitmap(width, height, config);
        bitmap.image.setRGB(0, 0, width, height, colors, offset, stride);
        bitmap.mutable = false;
        return bitmap;
    }

    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        if (dstWidth <= 0 || dstHeight <= 0) throw new IllegalArgumentException("width and height must be > 0");
        if (dstWidth == src.getWidth() && dstHeight == src.getHeight()) return src;
        BufferedImage out = new BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter
            ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src.readImage("createScaledBitmap()"), 0, 0, dstWidth, dstHeight, null);
        g.dispose();
        return new Bitmap(out, src.config, false);
    }

    // --- properties --------------------------------------------------------------------------

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getScaledWidth(int targetDensity) {
        return density == DENSITY_NONE || targetDensity == DENSITY_NONE ? width : (width * targetDensity + density / 2) / density;
    }

    public int getScaledHeight(int targetDensity) {
        return density == DENSITY_NONE || targetDensity == DENSITY_NONE ? height : (height * targetDensity + density / 2) / density;
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public boolean isMutable() {
        return mutable;
    }

    public boolean hasAlpha() {
        return hasAlpha;
    }

    public void setHasAlpha(boolean hasAlpha) {
        this.hasAlpha = hasAlpha;
    }

    public boolean isPremultiplied() {
        return true;
    }

    public void setPremultiplied(boolean premultiplied) {}

    public int getDensity() {
        return density;
    }

    public void setDensity(int density) {
        this.density = density;
    }

    public int getGenerationId() {
        return generationId;
    }

    public int getRowBytes() {
        return width * bytesPerPixel();
    }

    public int getByteCount() {
        return getRowBytes() * height;
    }

    public int getAllocationByteCount() {
        return getByteCount();
    }

    private int bytesPerPixel() {
        return switch (config) {
            case ALPHA_8 -> 1;
            case RGB_565, ARGB_4444 -> 2;
            case RGBA_F16 -> 8;
            default -> 4;
        };
    }

    public void recycle() {
        image = null;
    }

    public boolean isRecycled() {
        return image == null;
    }

    public void prepareToDraw() {}

    // --- pixels ------------------------------------------------------------------------------

    private void checkPixel(int x, int y) {
        if (x < 0 || x >= width) throw new IllegalArgumentException("x must be >= 0 and < bitmap.width()");
        if (y < 0 || y >= height) throw new IllegalArgumentException("y must be >= 0 and < bitmap.height()");
    }

    public int getPixel(int x, int y) {
        BufferedImage img = readImage("getPixel()");
        checkPixel(x, y);
        return img.getRGB(x, y);
    }

    public void setPixel(int x, int y, int color) {
        BufferedImage img = readImage("setPixel()");
        checkPixel(x, y);
        img.setRGB(x, y, color);
        generationId++;
    }

    private void checkRegion(int[] pixels, int offset, int stride, int x, int y, int w, int h) {
        if (w == 0 || h == 0) return;
        checkPixel(x, y);
        if (w < 0 || h < 0 || x + w > width || y + h > height) throw new IllegalArgumentException("region outside the bitmap");
        if (Math.abs(stride) < w) throw new IllegalArgumentException("abs(stride) must be >= width");
        long last = offset + (long) (h - 1) * stride + w - 1;
        if (offset < 0 || last >= pixels.length || offset + (long) (h - 1) * stride < 0) throw new ArrayIndexOutOfBoundsException();
    }

    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        BufferedImage img = readImage("getPixels()");
        checkRegion(pixels, offset, stride, x, y, width, height);
        if (width == 0 || height == 0) return;
        img.getRGB(x, y, width, height, pixels, offset, stride);
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        BufferedImage img = readImage("setPixels()");
        checkRegion(pixels, offset, stride, x, y, width, height);
        if (width == 0 || height == 0) return;
        img.setRGB(x, y, width, height, pixels, offset, stride);
        generationId++;
    }

    public void eraseColor(int color) {
        BufferedImage img = readImage("eraseColor()");
        int[] row = new int[width];
        Arrays.fill(row, color);
        for (int r = 0; r < height; r++) img.setRGB(0, r, width, 1, row, 0, width);
        generationId++;
    }

    public Bitmap copy(Config config, boolean isMutable) {
        Bitmap copy = createBitmap(this);
        copy.config = config == null ? this.config : config;
        copy.mutable = isMutable;
        copy.hasAlpha = hasAlpha;
        copy.density = density;
        return copy;
    }

    public boolean sameAs(Bitmap other) {
        if (other == this) return true;
        if (other == null || other.width != width || other.height != height || image == null || other.image == null) return false;
        int[] a = new int[width];
        int[] b = new int[width];
        for (int r = 0; r < height; r++) {
            image.getRGB(0, r, width, 1, a, 0, width);
            other.image.getRGB(0, r, width, 1, b, 0, width);
            if (!Arrays.equals(a, b)) return false;
        }
        return true;
    }

    /**
     * Encodes the bitmap to {@code stream}, which stays open. ImageIO can't write WebP, so
     * WEBP/WEBP_LOSSY are written as JPEG (or PNG at quality 100) and WEBP_LOSSLESS as PNG.
     */
    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        if (image == null) throw new IllegalStateException("Can't compress a recycled bitmap");
        if (quality < 0 || quality > 100) throw new IllegalArgumentException("quality must be 0..100");
        if (stream == null || format == null) throw new NullPointerException();
        try {
            return ImageCodec.encode(image, format, quality, stream);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(android.os.Parcel dest, int flags) {
        dest.writeValue(this);
    }
}
