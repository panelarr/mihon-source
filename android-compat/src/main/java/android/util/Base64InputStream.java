// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes (or, with {@code encode}, encodes) Base64 while reading. The whole underlying stream
 * is converted on the first read; the flags are those of {@link Base64}.
 */
public class Base64InputStream extends FilterInputStream {
    private final int flags;
    private final boolean encode;
    private InputStream converted;

    public Base64InputStream(InputStream in, int flags) {
        this(in, flags, false);
    }

    public Base64InputStream(InputStream in, int flags, boolean encode) {
        super(in);
        this.flags = flags;
        this.encode = encode;
    }

    private InputStream converted() throws IOException {
        if (converted == null) {
            byte[] input = in.readAllBytes();
            try {
                converted = new ByteArrayInputStream(encode ? Base64.encode(input, flags) : Base64.decode(input, flags));
            } catch (IllegalArgumentException e) {
                throw new IOException("bad base-64", e);
            }
        }
        return converted;
    }

    @Override
    public int read() throws IOException {
        return converted().read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return converted().read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return converted().skip(n);
    }

    @Override
    public int available() throws IOException {
        return converted == null ? 0 : converted.available();
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(int readlimit) {}

    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public void close() throws IOException {
        if ((flags & Base64.NO_CLOSE) == 0) in.close();
    }
}
