// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import android.content.res.Resources;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decodes JPEG, PNG, GIF (first frame), BMP and WebP into {@link Bitmap}s. As on Android,
 * data that can't be decoded gives null rather than an exception.
 */
public class BitmapFactory {
    public BitmapFactory() {}

    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public boolean inMutable;
        public boolean inPremultiplied = true;
        public boolean inScaled = true;
        public boolean inDither;
        public int inDensity;
        public int inTargetDensity;
        public int inScreenDensity;
        public Bitmap inBitmap;
        public byte[] inTempStorage;
        public boolean inPreferQualityOverSpeed;
        public int outWidth;
        public int outHeight;
        public String outMimeType;
        public Bitmap.Config outConfig;
        public boolean mCancel;

        public Options() {}

        public void requestCancelDecode() {
            mCancel = true;
        }
    }

    /** Android rounds the sample size down to a power of two; below 1 means 1. */
    private static int sampleSize(Options opts) {
        int requested = opts == null ? 1 : opts.inSampleSize;
        return requested <= 1 ? 1 : Integer.highestOneBit(requested);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options opts) {
        if ((offset | length) < 0 || data.length < offset + length) throw new ArrayIndexOutOfBoundsException();
        int sample = sampleSize(opts);
        if (opts != null) {
            opts.outWidth = -1;
            opts.outHeight = -1;
            opts.outMimeType = null;
            opts.outConfig = null;
        }
        if (opts != null && opts.inJustDecodeBounds) {
            ImageCodec.Header header = ImageCodec.header(data, offset, length);
            if (header != null) {
                opts.outWidth = (header.width() + sample - 1) / sample;
                opts.outHeight = (header.height() + sample - 1) / sample;
                opts.outMimeType = header.mimeType();
                opts.outConfig = opts.inPreferredConfig;
            }
            return null;
        }
        ImageCodec.Decoded decoded = ImageCodec.decode(data, offset, length, sample);
        if (decoded == null) return null;
        Bitmap.Config config = opts == null || opts.inPreferredConfig == null ? Bitmap.Config.ARGB_8888 : opts.inPreferredConfig;
        Bitmap bitmap = new Bitmap(decoded.image(), config, opts != null && opts.inMutable);
        if (opts != null) {
            opts.outWidth = bitmap.getWidth();
            opts.outHeight = bitmap.getHeight();
            opts.outMimeType = decoded.mimeType();
            opts.outConfig = config;
        }
        return bitmap;
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }

    public static Bitmap decodeStream(InputStream is, Rect outPadding, Options opts) {
        if (is == null) return null;
        byte[] data;
        try {
            data = is.readAllBytes();
        } catch (IOException e) {
            return null;
        }
        if (outPadding != null) outPadding.set(-1, -1, -1, -1);
        return decodeByteArray(data, 0, data.length, opts);
    }

    public static Bitmap decodeStream(InputStream is) {
        return decodeStream(is, null, null);
    }

    public static Bitmap decodeFile(String pathName, Options opts) {
        try {
            byte[] data = Files.readAllBytes(Path.of(pathName));
            return decodeByteArray(data, 0, data.length, opts);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Bitmap decodeFile(String pathName) {
        return decodeFile(pathName, null);
    }

    public static Bitmap decodeFileDescriptor(FileDescriptor fd, Rect outPadding, Options opts) {
        try (InputStream in = new FileInputStream(fd)) {
            return decodeStream(in, outPadding, opts);
        } catch (IOException e) {
            return null;
        }
    }

    public static Bitmap decodeFileDescriptor(FileDescriptor fd) {
        return decodeFileDescriptor(fd, null, null);
    }

    /** There are no resources on this host. */
    public static Bitmap decodeResource(Resources res, int id, Options opts) {
        return null;
    }

    public static Bitmap decodeResource(Resources res, int id) {
        return null;
    }
}
