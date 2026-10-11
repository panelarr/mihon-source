// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics.pdf;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

/** Android's PdfRenderer. PDF rendering is not available here: the constructor throws. */
public final class PdfRenderer implements AutoCloseable {
    public PdfRenderer(ParcelFileDescriptor input) throws IOException {
        throw new IOException("PDF rendering is not available in mihon-source");
    }

    public int getPageCount() {
        throw new IllegalStateException("Already closed");
    }

    public boolean shouldScaleForPrinting() {
        return false;
    }

    public Page openPage(int index) {
        throw new IllegalStateException("Already closed");
    }

    @Override
    public void close() {}

    public final class Page implements AutoCloseable {
        public static final int RENDER_MODE_FOR_DISPLAY = 1;
        public static final int RENDER_MODE_FOR_PRINT = 2;

        private Page() {}

        public int getIndex() {
            return 0;
        }

        public int getWidth() {
            return 0;
        }

        public int getHeight() {
            return 0;
        }

        public void render(Bitmap destination, Rect destClip, Matrix transform, int renderMode) {
            throw new IllegalStateException("Already closed");
        }

        @Override
        public void close() {}
    }
}
