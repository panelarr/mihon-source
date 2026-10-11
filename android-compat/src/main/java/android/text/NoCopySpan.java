// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

/** Marker for spans that are not copied with their text. */
public interface NoCopySpan {
    class Concrete implements NoCopySpan {
        public Concrete() {}
    }
}
