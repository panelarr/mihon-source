// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import java.util.Locale;
import java.util.Map;

/** Color ints (0xAARRGGBB, not premultiplied) and helpers, as on Android. */
public class Color {
    public static final int BLACK = 0xFF000000;
    public static final int DKGRAY = 0xFF444444;
    public static final int GRAY = 0xFF888888;
    public static final int LTGRAY = 0xFFCCCCCC;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int RED = 0xFFFF0000;
    public static final int GREEN = 0xFF00FF00;
    public static final int BLUE = 0xFF0000FF;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int CYAN = 0xFF00FFFF;
    public static final int MAGENTA = 0xFFFF00FF;
    public static final int TRANSPARENT = 0;

    private static final Map<String, Integer> NAMES = Map.ofEntries(
        Map.entry("black", BLACK),
        Map.entry("darkgray", DKGRAY),
        Map.entry("darkgrey", DKGRAY),
        Map.entry("gray", GRAY),
        Map.entry("grey", GRAY),
        Map.entry("lightgray", LTGRAY),
        Map.entry("lightgrey", LTGRAY),
        Map.entry("white", WHITE),
        Map.entry("red", RED),
        Map.entry("green", GREEN),
        Map.entry("blue", BLUE),
        Map.entry("yellow", YELLOW),
        Map.entry("cyan", CYAN),
        Map.entry("magenta", MAGENTA),
        Map.entry("aqua", 0xFF00FFFF),
        Map.entry("fuchsia", 0xFFFF00FF),
        Map.entry("lime", 0xFF00FF00),
        Map.entry("maroon", 0xFF800000),
        Map.entry("navy", 0xFF000080),
        Map.entry("olive", 0xFF808000),
        Map.entry("purple", 0xFF800080),
        Map.entry("silver", 0xFFC0C0C0),
        Map.entry("teal", 0xFF008080));

    public Color() {}

    public static int alpha(int color) {
        return color >>> 24;
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int rgb(int red, int green, int blue) {
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    public static int rgb(float red, float green, float blue) {
        return rgb(Math.round(red * 255), Math.round(green * 255), Math.round(blue * 255));
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    public static int argb(float alpha, float red, float green, float blue) {
        return argb(Math.round(alpha * 255), Math.round(red * 255), Math.round(green * 255), Math.round(blue * 255));
    }

    public static float luminance(int color) {
        double r = linear(red(color) / 255.0);
        double g = linear(green(color) / 255.0);
        double b = linear(blue(color) / 255.0);
        return (float) (0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** Parses "#RRGGBB", "#AARRGGBB" or a basic color name. */
    public static int parseColor(String colorString) {
        if (colorString.startsWith("#")) {
            String hex = colorString.substring(1);
            if (hex.length() == 6 || hex.length() == 8) {
                try {
                    long value = Long.parseLong(hex, 16);
                    if (hex.length() == 6) value |= 0xFF000000L;
                    return (int) value;
                } catch (NumberFormatException ignored) {
                    // fall through to the error below
                }
            }
        } else {
            Integer named = NAMES.get(colorString.toLowerCase(Locale.ROOT));
            if (named != null) return named;
        }
        throw new IllegalArgumentException("Unknown color");
    }

    public static void colorToHSV(int color, float[] hsv) {
        float[] result = java.awt.Color.RGBtoHSB(red(color), green(color), blue(color), null);
        hsv[0] = result[0] * 360f;
        hsv[1] = result[1];
        hsv[2] = result[2];
    }

    public static void RGBToHSV(int red, int green, int blue, float[] hsv) {
        colorToHSV(rgb(red, green, blue), hsv);
    }

    public static int HSVToColor(float[] hsv) {
        return HSVToColor(0xFF, hsv);
    }

    public static int HSVToColor(int alpha, float[] hsv) {
        int rgb = java.awt.Color.HSBtoRGB(hsv[0] / 360f, hsv[1], hsv[2]);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
