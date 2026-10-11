// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

/** Changes the color of each pixel drawn with a Paint. */
public class ColorFilter {
    public ColorFilter() {}

    /** Maps one non-premultiplied ARGB color. The base filter changes nothing. */
    int filter(int argb) {
        return argb;
    }
}
