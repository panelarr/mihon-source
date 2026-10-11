// SPDX-License-Identifier: GPL-3.0-or-later

package android.icu.text;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Locale;

/**
 * ICU's BreakIterator on top of java.text.BreakIterator. Word instances report ICU rule
 * statuses (none, number, letter, kana, ideographic) for the segment before the current boundary.
 */
public abstract class BreakIterator implements Cloneable {
    public static final int DONE = -1;
    public static final int WORD_NONE = 0;
    public static final int WORD_NONE_LIMIT = 100;
    public static final int WORD_NUMBER = 100;
    public static final int WORD_NUMBER_LIMIT = 200;
    public static final int WORD_LETTER = 200;
    public static final int WORD_LETTER_LIMIT = 300;
    public static final int WORD_KANA = 300;
    public static final int WORD_KANA_LIMIT = 400;
    public static final int WORD_IDEO = 400;
    public static final int WORD_IDEO_LIMIT = 500;

    protected BreakIterator() {}

    public static BreakIterator getWordInstance() {
        return getWordInstance(Locale.getDefault());
    }

    public static BreakIterator getWordInstance(Locale where) {
        return new JdkBreakIterator(java.text.BreakIterator.getWordInstance(where), true);
    }

    public static BreakIterator getCharacterInstance() {
        return getCharacterInstance(Locale.getDefault());
    }

    public static BreakIterator getCharacterInstance(Locale where) {
        return new JdkBreakIterator(java.text.BreakIterator.getCharacterInstance(where), false);
    }

    public static BreakIterator getLineInstance() {
        return getLineInstance(Locale.getDefault());
    }

    public static BreakIterator getLineInstance(Locale where) {
        return new JdkBreakIterator(java.text.BreakIterator.getLineInstance(where), false);
    }

    public static BreakIterator getSentenceInstance() {
        return getSentenceInstance(Locale.getDefault());
    }

    public static BreakIterator getSentenceInstance(Locale where) {
        return new JdkBreakIterator(java.text.BreakIterator.getSentenceInstance(where), false);
    }

    public abstract int first();

    public abstract int last();

    public abstract int next(int n);

    public abstract int next();

    public abstract int previous();

    public abstract int following(int offset);

    public int preceding(int offset) {
        int pos = following(offset);
        while (pos >= offset && pos != DONE) pos = previous();
        return pos;
    }

    public boolean isBoundary(int offset) {
        if (offset == 0) return true;
        return following(offset - 1) == offset;
    }

    public abstract int current();

    public int getRuleStatus() {
        return 0;
    }

    public int getRuleStatusVec(int[] fillInArray) {
        if (fillInArray != null && fillInArray.length > 0) fillInArray[0] = getRuleStatus();
        return 1;
    }

    public abstract CharacterIterator getText();

    public void setText(String newText) {
        setText(new StringCharacterIterator(newText));
    }

    public void setText(CharSequence newText) {
        setText(newText.toString());
    }

    public abstract void setText(CharacterIterator newText);

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    private static final class JdkBreakIterator extends BreakIterator {
        private java.text.BreakIterator delegate;
        private final boolean words;
        private String text = "";

        JdkBreakIterator(java.text.BreakIterator delegate, boolean words) {
            this.delegate = delegate;
            this.words = words;
        }

        @Override
        public int first() {
            return delegate.first();
        }

        @Override
        public int last() {
            return delegate.last();
        }

        @Override
        public int next(int n) {
            return delegate.next(n);
        }

        @Override
        public int next() {
            return delegate.next();
        }

        @Override
        public int previous() {
            return delegate.previous();
        }

        @Override
        public int following(int offset) {
            return delegate.following(offset);
        }

        @Override
        public int preceding(int offset) {
            return delegate.preceding(offset);
        }

        @Override
        public boolean isBoundary(int offset) {
            return delegate.isBoundary(offset);
        }

        @Override
        public int current() {
            return delegate.current();
        }

        @Override
        public CharacterIterator getText() {
            return delegate.getText();
        }

        @Override
        public void setText(CharacterIterator newText) {
            StringBuilder sb = new StringBuilder();
            for (char c = newText.first(); c != CharacterIterator.DONE; c = newText.next()) sb.append(c);
            text = sb.toString();
            delegate.setText(new StringCharacterIterator(text));
        }

        @Override
        public int getRuleStatus() {
            if (!words) return 0;
            int end = delegate.current();
            if (end <= 0) return WORD_NONE;
            int start = delegate.preceding(end);
            delegate.following(end - 1); // restores the position to end
            if (start == DONE) start = 0;
            int status = WORD_NONE;
            for (int i = start; i < end && i < text.length(); ) {
                int cp = text.codePointAt(i);
                Character.UnicodeScript script = Character.UnicodeScript.of(cp);
                if (script == Character.UnicodeScript.HAN) return WORD_IDEO;
                if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) return WORD_KANA;
                if (Character.isLetter(cp)) status = WORD_LETTER;
                else if (Character.isDigit(cp) && status == WORD_NONE) status = WORD_NUMBER;
                i += Character.charCount(cp);
            }
            return status;
        }

        @Override
        public Object clone() {
            JdkBreakIterator copy = (JdkBreakIterator) super.clone();
            copy.delegate = (java.text.BreakIterator) delegate.clone();
            return copy;
        }
    }
}
