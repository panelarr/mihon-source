// SPDX-License-Identifier: GPL-3.0-or-later

package android.content;

/** Thrown when no activity can handle an Intent. Every startActivity call throws it here. */
public class ActivityNotFoundException extends RuntimeException {
    public ActivityNotFoundException() {}

    public ActivityNotFoundException(String name) {
        super(name);
    }
}
