// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

/** A CharSequence that can copy a range of its chars into an array. */
public interface GetChars extends CharSequence {
    void getChars(int start, int end, char[] dest, int destoff);
}
