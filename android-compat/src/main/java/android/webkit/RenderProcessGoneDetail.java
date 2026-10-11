// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

/** Why a WebView's renderer went away. */
public abstract class RenderProcessGoneDetail {
    public RenderProcessGoneDetail() {}

    public abstract boolean didCrash();

    public abstract int rendererPriorityAtExit();
}
