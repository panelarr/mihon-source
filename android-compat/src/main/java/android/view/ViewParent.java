// SPDX-License-Identifier: GPL-3.0-or-later

package android.view;

/** Android's ViewParent, reduced to the members used here. */
public interface ViewParent {
    ViewParent getParent();

    void requestLayout();
}
