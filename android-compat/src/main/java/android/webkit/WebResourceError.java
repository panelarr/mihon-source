// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

/** A failed resource load. */
public abstract class WebResourceError {
    WebResourceError() {}

    public abstract int getErrorCode();

    public abstract CharSequence getDescription();
}
