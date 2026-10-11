// SPDX-License-Identifier: GPL-3.0-or-later

package android.text.method;

/** Makes links in a text view clickable. A no-op here. */
public class LinkMovementMethod implements MovementMethod {
    private static final LinkMovementMethod INSTANCE = new LinkMovementMethod();

    public LinkMovementMethod() {}

    public static MovementMethod getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean canSelectArbitrarily() {
        return true;
    }
}
