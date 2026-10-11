// SPDX-License-Identifier: GPL-3.0-or-later

package android.widget;

import android.content.Context;
import java.util.logging.Logger;

/** Android's Toast. {@link #show()} logs the text instead of showing it. */
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    private static final Logger LOG = Logger.getLogger("android.widget.Toast");

    private CharSequence text;
    private int duration;

    public Toast(Context context) {}

    public static Toast makeText(Context context, CharSequence text, int duration) {
        Toast toast = new Toast(context);
        toast.text = text;
        toast.duration = duration;
        return toast;
    }

    public static Toast makeText(Context context, int resId, int duration) {
        return makeText(context, context.getResources().getText(resId), duration);
    }

    public void show() {
        LOG.info(() -> "Toast: " + text);
    }

    public void cancel() {}

    public void setText(CharSequence s) {
        this.text = s;
    }

    public void setText(int resId) {}

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getDuration() {
        return duration;
    }

    public void setGravity(int gravity, int xOffset, int yOffset) {}
}
