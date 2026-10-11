// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;

/** A font family and style, backed by a java.awt.Font of size 1. */
public class Typeface {
    static {
        Headless.init();
    }

    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    public static final Typeface DEFAULT = new Typeface(new Font(Font.SANS_SERIF, Font.PLAIN, 1), NORMAL);
    public static final Typeface DEFAULT_BOLD = new Typeface(new Font(Font.SANS_SERIF, Font.BOLD, 1), BOLD);
    public static final Typeface SANS_SERIF = new Typeface(new Font(Font.SANS_SERIF, Font.PLAIN, 1), NORMAL);
    public static final Typeface SERIF = new Typeface(new Font(Font.SERIF, Font.PLAIN, 1), NORMAL);
    public static final Typeface MONOSPACE = new Typeface(new Font(Font.MONOSPACED, Font.PLAIN, 1), NORMAL);

    final Font font;
    private final int style;

    Typeface(Font font, int style) {
        this.font = font;
        this.style = style;
    }

    private static int awtStyle(int style) {
        return ((style & BOLD) != 0 ? Font.BOLD : 0) | ((style & ITALIC) != 0 ? Font.ITALIC : 0);
    }

    public static Typeface create(String familyName, int style) {
        String family = familyName == null ? Font.SANS_SERIF : switch (familyName) {
            case "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-condensed" -> Font.SANS_SERIF;
            case "serif" -> Font.SERIF;
            case "monospace" -> Font.MONOSPACED;
            default -> familyName;
        };
        return new Typeface(new Font(family, awtStyle(style), 1), style);
    }

    public static Typeface create(Typeface family, int style) {
        Typeface base = family == null ? DEFAULT : family;
        return new Typeface(base.font.deriveFont(awtStyle(style)), style);
    }

    public static Typeface create(Typeface family, int weight, boolean italic) {
        return create(family, (weight >= 600 ? BOLD : NORMAL) | (italic ? ITALIC : NORMAL));
    }

    public static Typeface defaultFromStyle(int style) {
        return create(DEFAULT, style);
    }

    /** Loads a TrueType or OpenType font file. */
    public static Typeface createFromFile(File file) {
        try {
            return new Typeface(Font.createFont(Font.TRUETYPE_FONT, file).deriveFont(1f), NORMAL);
        } catch (FontFormatException | IOException e) {
            throw new RuntimeException("Font asset not found " + file, e);
        }
    }

    public static Typeface createFromFile(String path) {
        return createFromFile(new File(path));
    }

    public int getStyle() {
        return style;
    }

    public final boolean isBold() {
        return (style & BOLD) != 0;
    }

    public final boolean isItalic() {
        return (style & ITALIC) != 0;
    }

    public int getWeight() {
        return isBold() ? 700 : 400;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Typeface t && style == t.style && font.equals(t.font);
    }

    @Override
    public int hashCode() {
        return 31 * font.hashCode() + style;
    }
}
