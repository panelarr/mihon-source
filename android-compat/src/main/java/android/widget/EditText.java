// SPDX-License-Identifier: GPL-3.0-or-later

package android.widget;

import android.content.Context;
import android.text.Editable;
import android.text.SpannableStringBuilder;

/** Android's EditText: a TextView whose text is an {@link Editable}. */
public class EditText extends TextView {
    private int selectionStart;
    private int selectionEnd;

    public EditText(Context context) {
        super(context);
        setText("");
    }

    @Override
    CharSequence wrap(CharSequence value) {
        return value instanceof Editable e ? e : new SpannableStringBuilder(value == null ? "" : value);
    }

    @Override
    public Editable getText() {
        CharSequence text = super.getText();
        // Only null while the TextView constructor runs.
        return text instanceof Editable e ? e : new SpannableStringBuilder(text);
    }

    public void setSelection(int start, int stop) {
        selectionStart = start;
        selectionEnd = stop;
    }

    public void setSelection(int index) {
        setSelection(index, index);
    }

    public void selectAll() {
        setSelection(0, length());
    }

    public void extendSelection(int index) {
        selectionEnd = index;
    }

    public int getSelectionStart() {
        return selectionStart;
    }

    public int getSelectionEnd() {
        return selectionEnd;
    }
}
