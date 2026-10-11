// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

/** A ColorFilter that applies a {@link ColorMatrix}. */
public class ColorMatrixColorFilter extends ColorFilter {
    private final ColorMatrix matrix;

    public ColorMatrixColorFilter(ColorMatrix matrix) {
        this.matrix = new ColorMatrix(matrix);
    }

    public ColorMatrixColorFilter(float[] array) {
        this.matrix = new ColorMatrix(array);
    }

    public void getColorMatrix(ColorMatrix colorMatrix) {
        colorMatrix.set(matrix);
    }

    @Override
    int filter(int argb) {
        return matrix.apply(argb);
    }
}
