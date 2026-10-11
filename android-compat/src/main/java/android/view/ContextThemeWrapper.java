// SPDX-License-Identifier: GPL-3.0-or-later

package android.view;

import android.content.Context;
import android.content.ContextWrapper;

/** A ContextWrapper with a theme. Themes don't exist here; the id is only stored. */
public class ContextThemeWrapper extends ContextWrapper {
    private int themeResId;

    public ContextThemeWrapper() {
        super(null);
    }

    public ContextThemeWrapper(Context base, int themeResId) {
        super(base);
        this.themeResId = themeResId;
    }

    public void setTheme(int resid) {
        this.themeResId = resid;
    }

    public int getThemeResId() {
        return themeResId;
    }
}
