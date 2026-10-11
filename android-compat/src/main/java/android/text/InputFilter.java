// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

/** Filters edits to an Editable. Stored by the host's text views, never applied. */
public interface InputFilter {
    CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend);

    class LengthFilter implements InputFilter {
        private final int max;

        public LengthFilter(int max) {
            this.max = max;
        }

        public int getMax() {
            return max;
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            int keep = max - (dest.length() - (dend - dstart));
            if (keep <= 0) return "";
            if (keep >= end - start) return null;
            return source.subSequence(start, start + keep);
        }
    }

    class AllCaps implements InputFilter {
        public AllCaps() {}

        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            String upper = source.subSequence(start, end).toString().toUpperCase(java.util.Locale.ROOT);
            return upper.contentEquals(source.subSequence(start, end)) ? null : upper;
        }
    }
}
