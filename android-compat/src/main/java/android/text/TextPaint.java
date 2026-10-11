// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

import android.graphics.Paint;

/** A Paint with the extra text state Android's layouts use. */
public class TextPaint extends Paint {
    public int bgColor;
    public int baselineShift;
    public int linkColor;
    public int[] drawableState;
    public float density = 1.0f;
    public int underlineColor;

    public TextPaint() {
        super();
    }

    public TextPaint(int flags) {
        super(flags);
    }

    public TextPaint(Paint p) {
        super(p);
    }

    public void set(TextPaint tp) {
        super.set(tp);
        bgColor = tp.bgColor;
        baselineShift = tp.baselineShift;
        linkColor = tp.linkColor;
        drawableState = tp.drawableState;
        density = tp.density;
        underlineColor = tp.underlineColor;
    }
}
