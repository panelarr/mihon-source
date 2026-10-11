// SPDX-License-Identifier: GPL-3.0-or-later

package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/** Android's FrameLayout. Keeps its children; lays out nothing. */
public class FrameLayout extends ViewGroup {
    public FrameLayout(Context context) {
        super(context);
    }

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public int gravity = -1;

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(int width, int height, int gravity) {
            super(width, height);
            this.gravity = gravity;
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }
}
