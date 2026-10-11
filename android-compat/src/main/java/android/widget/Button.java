// SPDX-License-Identifier: GPL-3.0-or-later

package android.widget;

import android.content.Context;

/** Android's Button. Clicks only happen through {@link #performClick()}. */
public class Button extends TextView {
    public Button(Context context) {
        super(context);
    }
}
