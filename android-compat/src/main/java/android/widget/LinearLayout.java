// SPDX-License-Identifier: GPL-3.0-or-later

package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/** Android's LinearLayout. Keeps its children and orientation; lays out nothing. */
public class LinearLayout extends ViewGroup {
    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    private int orientation = HORIZONTAL;

    public LinearLayout(Context context) {
        super(context);
    }

    public void setOrientation(int orientation) {
        this.orientation = orientation;
    }

    public int getOrientation() {
        return orientation;
    }

    public void setGravity(int gravity) {}

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public float weight;
        public int gravity = -1;

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(int width, int height, float weight) {
            super(width, height);
            this.weight = weight;
        }

        public LayoutParams(ViewGroup.LayoutParams source) {
            super(source);
        }
    }
}
