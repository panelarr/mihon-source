// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

/** Screen metrics. The host reports a fixed 1080x2400 phone screen at 420 dpi. */
public class DisplayMetrics {
    public static final int DENSITY_DEFAULT = 160;
    public static final int DENSITY_XXHIGH = 480;

    public int widthPixels;
    public int heightPixels;
    public float density;
    public int densityDpi;
    public float scaledDensity;
    public float xdpi;
    public float ydpi;

    public DisplayMetrics() {}

    public void setTo(DisplayMetrics o) {
        widthPixels = o.widthPixels;
        heightPixels = o.heightPixels;
        density = o.density;
        densityDpi = o.densityDpi;
        scaledDensity = o.scaledDensity;
        xdpi = o.xdpi;
        ydpi = o.ydpi;
    }

    public void setToDefaults() {
        widthPixels = 0;
        heightPixels = 0;
        density = 1f;
        densityDpi = DENSITY_DEFAULT;
        scaledDensity = 1f;
        xdpi = DENSITY_DEFAULT;
        ydpi = DENSITY_DEFAULT;
    }

    @Override
    public String toString() {
        return "DisplayMetrics{density=" + density + ", width=" + widthPixels + ", height=" + heightPixels + "}";
    }
}
