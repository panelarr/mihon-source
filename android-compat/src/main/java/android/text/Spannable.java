// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

/** Text whose spans can be changed. */
public interface Spannable extends Spanned {
    void setSpan(Object what, int start, int end, int flags);

    void removeSpan(Object what);

    class Factory {
        private static final Factory INSTANCE = new Factory();

        public Factory() {}

        public static Factory getInstance() {
            return INSTANCE;
        }

        public Spannable newSpannable(CharSequence source) {
            return new SpannableString(source);
        }
    }
}
