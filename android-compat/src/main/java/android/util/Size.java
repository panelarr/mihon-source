// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

/** An immutable width and height. */
public final class Size {
    private final int width;
    private final int height;

    public Size(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public static Size parseSize(String string) {
        int sep = string.indexOf('x');
        if (sep < 0) sep = string.indexOf('*');
        if (sep < 0) throw new NumberFormatException("Invalid Size: \"" + string + "\"");
        return new Size(Integer.parseInt(string.substring(0, sep)), Integer.parseInt(string.substring(sep + 1)));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Size s && width == s.width && height == s.height;
    }

    @Override
    public int hashCode() {
        return height ^ ((width << 16) | (width >>> 16));
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }
}
