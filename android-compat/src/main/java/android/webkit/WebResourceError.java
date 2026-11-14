// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

/** A failed resource load. */
public abstract class WebResourceError {
    /** Hidden on Android; public here so the host's browser can report errors. */
    public WebResourceError() {}

    public abstract int getErrorCode();

    public abstract CharSequence getDescription();
}
