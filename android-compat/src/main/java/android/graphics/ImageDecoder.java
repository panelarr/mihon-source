// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import android.util.Size;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * Android's ImageDecoder on top of the same decoders as {@link BitmapFactory}. Supports a
 * header listener with target size, sample size, crop and mutability; the allocator is ignored.
 * Animated images decode to their first frame.
 */
public final class ImageDecoder implements AutoCloseable {
    public static final int ALLOCATOR_DEFAULT = 0;
    public static final int ALLOCATOR_SOFTWARE = 1;
    public static final int ALLOCATOR_SHARED_MEMORY = 2;
    public static final int ALLOCATOR_HARDWARE = 3;
    public static final int MEMORY_POLICY_DEFAULT = 1;
    public static final int MEMORY_POLICY_LOW_RAM = 0;

    public abstract static class Source {
        Source() {}

        abstract byte[] bytes() throws IOException;
    }

    private static final class BytesSource extends Source {
        private final byte[] data;

        BytesSource(byte[] data) {
            this.data = data;
        }

        @Override
        byte[] bytes() {
            return data;
        }
    }

    private static final class FileSource extends Source {
        private final File file;

        FileSource(File file) {
            this.file = file;
        }

        @Override
        byte[] bytes() throws IOException {
            return Files.readAllBytes(file.toPath());
        }
    }

    public static class ImageInfo {
        private final Size size;
        private final String mimeType;

        ImageInfo(Size size, String mimeType) {
            this.size = size;
            this.mimeType = mimeType;
        }

        public Size getSize() {
            return size;
        }

        public String getMimeType() {
            return mimeType;
        }

        public boolean isAnimated() {
            return false;
        }
    }

    public interface OnHeaderDecodedListener {
        void onHeaderDecoded(ImageDecoder decoder, ImageInfo info, Source source);
    }

    public interface OnPartialImageListener {
        boolean onPartialImage(DecodeException exception);
    }

    public static final class DecodeException extends IOException {
        public static final int SOURCE_EXCEPTION = 1;
        public static final int SOURCE_INCOMPLETE = 2;
        public static final int SOURCE_MALFORMED_DATA = 3;

        private final int error;

        public DecodeException(int error, String msg, Throwable cause) {
            super(msg, cause);
            this.error = error;
        }

        public int getError() {
            return error;
        }
    }

    private int allocator = ALLOCATOR_DEFAULT;
    private int targetWidth;
    private int targetHeight;
    private int sampleSize = 1;
    private boolean mutableRequired;
    private Rect crop;
    private int memorySizePolicy = MEMORY_POLICY_DEFAULT;

    private ImageDecoder() {}

    public static Source createSource(byte[] data) {
        return new BytesSource(data.clone());
    }

    public static Source createSource(byte[] data, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        return new BytesSource(copy);
    }

    public static Source createSource(ByteBuffer buffer) {
        ByteBuffer view = buffer.duplicate();
        byte[] data = new byte[view.remaining()];
        view.get(data);
        return new BytesSource(data);
    }

    public static Source createSource(File file) {
        return new FileSource(file);
    }

    public static boolean isMimeTypeSupported(String mimeType) {
        return switch (mimeType) {
            case "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp" -> true;
            default -> false;
        };
    }

    public static Bitmap decodeBitmap(Source src) throws IOException {
        return decodeBitmap(src, null);
    }

    public static Bitmap decodeBitmap(Source src, OnHeaderDecodedListener listener) throws IOException {
        byte[] data = src.bytes();
        ImageCodec.Header header = ImageCodec.header(data, 0, data.length);
        if (header == null) throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to create image decoder with message 'unimplemented'Input contained an error.", null);
        ImageDecoder decoder = new ImageDecoder();
        if (listener != null) listener.onHeaderDecoded(decoder, new ImageInfo(new Size(header.width(), header.height()), header.mimeType()), src);
        ImageCodec.Decoded decoded = ImageCodec.decode(data, 0, data.length, decoder.sampleSize);
        if (decoded == null) throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to decode image", null);
        Bitmap bitmap = new Bitmap(decoded.image(), Bitmap.Config.ARGB_8888, decoder.mutableRequired);
        if (decoder.targetWidth > 0 && decoder.targetHeight > 0) {
            bitmap = Bitmap.createScaledBitmap(bitmap, decoder.targetWidth, decoder.targetHeight, true);
        }
        if (decoder.crop != null) {
            Rect c = decoder.crop;
            bitmap = Bitmap.createBitmap(bitmap, c.left, c.top, c.width(), c.height());
        }
        if (decoder.mutableRequired && !bitmap.isMutable()) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        return bitmap;
    }

    public int getAllocator() {
        return allocator;
    }

    public void setAllocator(int allocator) {
        if (allocator < ALLOCATOR_DEFAULT || allocator > ALLOCATOR_HARDWARE) throw new IllegalArgumentException("invalid allocator " + allocator);
        this.allocator = allocator;
    }

    public void setTargetSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Dimensions must be positive!");
        this.targetWidth = width;
        this.targetHeight = height;
    }

    public void setTargetSampleSize(int sampleSize) {
        this.sampleSize = sampleSize <= 1 ? 1 : sampleSize;
    }

    public void setCrop(Rect subset) {
        this.crop = subset;
    }

    public Rect getCrop() {
        return crop;
    }

    public void setMutableRequired(boolean mutableRequired) {
        this.mutableRequired = mutableRequired;
    }

    public boolean isMutableRequired() {
        return mutableRequired;
    }

    public void setMemorySizePolicy(int policy) {
        this.memorySizePolicy = policy;
    }

    public int getMemorySizePolicy() {
        return memorySizePolicy;
    }

    public void setUnpremultipliedRequired(boolean unpremultipliedRequired) {}

    public void setOnPartialImageListener(OnPartialImageListener listener) {}

    @Override
    public void close() {}
}
