// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

/** Makes java.awt run headless before the graphics shims touch it, unless the host chose already. */
final class Headless {
    static {
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    private Headless() {}

    /** Loading this class is what sets the property; calling it from a static block is enough. */
    static void init() {}
}
