// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Decoding and encoding for the graphics shims, on ImageIO. JPEG, PNG, GIF and BMP use the
 * readers on the classpath (TwelveMonkeys adds CMYK and other JPEGs the JDK rejects); WebP is
 * decoded with TwelveMonkeys' reader, which is asked for directly so it works whatever the
 * thread's context class loader is. ImageIO can't write WebP, see {@link #encode}.
 */
final class ImageCodec {
    static {
        Headless.init();
        ImageIO.setUseCache(false);
    }

    private static final ImageReaderSpi WEBP = new WebPImageReaderSpi();

    private ImageCodec() {}

    record Header(int width, int height, String mimeType) {}

    record Decoded(BufferedImage image, String mimeType) {}

    private static ImageInputStream stream(byte[] data, int offset, int length) {
        return new MemoryCacheImageInputStream(new ByteArrayInputStream(data, offset, length));
    }

    private static boolean isWebP(byte[] d, int off, int len) {
        return len >= 12 && d[off] == 'R' && d[off + 1] == 'I' && d[off + 2] == 'F' && d[off + 3] == 'F'
            && d[off + 8] == 'W' && d[off + 9] == 'E' && d[off + 10] == 'B' && d[off + 11] == 'P';
    }

    private static ImageReader reader(byte[] data, int offset, int length, ImageInputStream in) throws IOException {
        if (isWebP(data, offset, length)) return WEBP.createReaderInstance();
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        return readers.hasNext() ? readers.next() : null;
    }

    private static String mimeType(ImageReader reader) {
        String[] types = reader.getOriginatingProvider() == null ? null : reader.getOriginatingProvider().getMIMETypes();
        if (types != null && types.length > 0) return types[0];
        try {
            return "image/" + reader.getFormatName().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads only the size and type, or returns null when the data isn't a known image. */
    static Header header(byte[] data, int offset, int length) {
        try (ImageInputStream in = stream(data, offset, length)) {
            ImageReader reader = reader(data, offset, length, in);
            if (reader == null) return null;
            try {
                reader.setInput(in, true, true);
                return new Header(reader.getWidth(0), reader.getHeight(0), mimeType(reader));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Decodes the first frame as ARGB, or returns null when the data can't be decoded. */
    static Decoded decode(byte[] data, int offset, int length, int sampleSize) {
        try (ImageInputStream in = stream(data, offset, length)) {
            ImageReader reader = reader(data, offset, length, in);
            if (reader == null) return null;
            try {
                reader.setInput(in, true, true);
                ImageReadParam param = reader.getDefaultReadParam();
                if (sampleSize > 1) param.setSourceSubsampling(sampleSize, sampleSize, 0, 0);
                BufferedImage image = reader.read(0, param);
                return new Decoded(toArgb(image), mimeType(reader));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) return image;
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return argb;
    }

    /**
     * Writes {@code image} to {@code out}, which is left open. WebP can't be written with
     * ImageIO, so WEBP and WEBP_LOSSY become JPEG when quality is below 100 and PNG otherwise,
     * and WEBP_LOSSLESS becomes PNG. Callers get valid image bytes, just not WebP ones.
     */
    static boolean encode(BufferedImage image, Bitmap.CompressFormat format, int quality, OutputStream out) throws IOException {
        int q = Math.max(0, Math.min(100, quality));
        boolean jpeg = switch (format) {
            case JPEG -> true;
            case PNG, WEBP_LOSSLESS -> false;
            case WEBP, WEBP_LOSSY -> q < 100;
        };
        return jpeg ? writeJpeg(image, q, out) : ImageIO.write(image, "png", new NonClosingStream(out));
    }

    private static boolean writeJpeg(BufferedImage image, int quality, OutputStream out) throws IOException {
        // JPEG has no alpha. Android drops it from premultiplied pixels, which is the same as
        // drawing the image over black.
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return false;
        ImageWriter writer = writers.next();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100f);
            writer.write(null, new IIOImage(rgb, null, null), param);
            return true;
        } finally {
            writer.dispose();
        }
    }

    /** ImageIO.write closes nothing it didn't open, but be explicit: the caller owns the stream. */
    private static final class NonClosingStream extends java.io.FilterOutputStream {
        NonClosingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
