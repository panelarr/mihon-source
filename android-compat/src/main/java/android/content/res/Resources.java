// SPDX-License-Identifier: GPL-3.0-or-later

package android.content.res;

import android.util.DisplayMetrics;

/**
 * Android's Resources. There are no resources on this host: lookups return empty values,
 * and the display metrics describe a fixed phone-sized screen.
 */
public class Resources {
    private static final Resources SYSTEM = new Resources();

    private final DisplayMetrics metrics = phoneMetrics();

    protected Resources() {}

    private static DisplayMetrics phoneMetrics() {
        DisplayMetrics m = new DisplayMetrics();
        m.widthPixels = 1080;
        m.heightPixels = 2400;
        m.densityDpi = 420;
        m.density = 420f / DisplayMetrics.DENSITY_DEFAULT;
        m.scaledDensity = m.density;
        m.xdpi = 420f;
        m.ydpi = 420f;
        return m;
    }

    public static Resources getSystem() {
        return SYSTEM;
    }

    public DisplayMetrics getDisplayMetrics() {
        return metrics;
    }

    public String getString(int id) {
        return "";
    }

    public String getString(int id, Object... formatArgs) {
        return "";
    }

    public CharSequence getText(int id) {
        return "";
    }

    public String[] getStringArray(int id) {
        return new String[0];
    }

    public int getColor(int id) {
        return 0;
    }

    public float getDimension(int id) {
        return 0f;
    }

    public int getDimensionPixelSize(int id) {
        return 0;
    }

    public int getIdentifier(String name, String defType, String defPackage) {
        return 0;
    }
}
