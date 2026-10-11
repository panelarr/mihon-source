// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import java.util.Arrays;

/**
 * A 4x5 matrix that transforms RGBA colors (0..255): each output channel is a weighted sum of
 * the input R, G, B, A plus an offset, stored row by row in 20 floats.
 */
public class ColorMatrix {
    private final float[] array = new float[20];

    public ColorMatrix() {
        reset();
    }

    public ColorMatrix(float[] src) {
        System.arraycopy(src, 0, array, 0, 20);
    }

    public ColorMatrix(ColorMatrix src) {
        System.arraycopy(src.array, 0, array, 0, 20);
    }

    public final float[] getArray() {
        return array;
    }

    public void reset() {
        Arrays.fill(array, 0f);
        array[0] = array[6] = array[12] = array[18] = 1f;
    }

    public void set(ColorMatrix src) {
        System.arraycopy(src.array, 0, array, 0, 20);
    }

    public void set(float[] src) {
        System.arraycopy(src, 0, array, 0, 20);
    }

    public void setScale(float rScale, float gScale, float bScale, float aScale) {
        Arrays.fill(array, 0f);
        array[0] = rScale;
        array[6] = gScale;
        array[12] = bScale;
        array[18] = aScale;
    }

    public void setSaturation(float sat) {
        reset();
        float inv = 1 - sat;
        float r = 0.213f * inv;
        float g = 0.715f * inv;
        float b = 0.072f * inv;
        array[0] = r + sat;
        array[1] = g;
        array[2] = b;
        array[5] = r;
        array[6] = g + sat;
        array[7] = b;
        array[10] = r;
        array[11] = g;
        array[12] = b + sat;
    }

    /** this = a * b: applying the result equals applying b, then a. */
    public void setConcat(ColorMatrix a, ColorMatrix b) {
        float[] out = new float[20];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 5; col++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += a.array[row * 5 + k] * b.array[k * 5 + col];
                if (col == 4) sum += a.array[row * 5 + 4];
                out[row * 5 + col] = sum;
            }
        }
        System.arraycopy(out, 0, array, 0, 20);
    }

    public void preConcat(ColorMatrix prematrix) {
        setConcat(this, prematrix);
    }

    public void postConcat(ColorMatrix postmatrix) {
        setConcat(postmatrix, this);
    }

    int apply(int argb) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int nr = channel(0, r, g, b, a);
        int ng = channel(5, r, g, b, a);
        int nb = channel(10, r, g, b, a);
        int na = channel(15, r, g, b, a);
        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    private int channel(int row, int r, int g, int b, int a) {
        float v = array[row] * r + array[row + 1] * g + array[row + 2] * b + array[row + 3] * a + array[row + 4];
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
