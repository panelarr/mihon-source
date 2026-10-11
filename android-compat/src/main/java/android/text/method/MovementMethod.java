// SPDX-License-Identifier: GPL-3.0-or-later

package android.text.method;

/** How a text view reacts to cursor movement. Nothing moves on this host. */
public interface MovementMethod {
    boolean canSelectArbitrarily();
}
