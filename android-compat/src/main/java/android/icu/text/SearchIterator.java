// SPDX-License-Identifier: GPL-3.0-or-later

package android.icu.text;

import java.text.CharacterIterator;

/** Base of ICU's text searches. */
public abstract class SearchIterator {
    public static final int DONE = -1;

    protected SearchIterator() {}

    public abstract int first();

    public abstract int next();

    public abstract int getMatchStart();

    public abstract int getMatchLength();

    public abstract String getMatchedText();

    public abstract void setTarget(CharacterIterator text);

    public abstract void reset();

    public abstract void setOverlapping(boolean allowOverlap);

    public abstract boolean isOverlapping();
}
