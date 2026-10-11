// SPDX-License-Identifier: GPL-3.0-or-later

package android.widget;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.MovementMethod;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Android's TextView as a data holder. {@link #setText(CharSequence)} notifies the registered
 * {@link TextWatcher}s, so validation code written for preference dialogs can run headless.
 */
public class TextView extends View {
    private CharSequence text = "";
    private CharSequence hint;
    private CharSequence error;
    private int inputType = android.text.InputType.TYPE_CLASS_TEXT;
    private int textColor = 0xFF000000;
    private float textSize = 14f;
    private int maxLines = Integer.MAX_VALUE;
    private int gravity;
    private InputFilter[] filters = new InputFilter[0];
    private MovementMethod movementMethod;
    private final List<TextWatcher> watchers = new ArrayList<>();

    public TextView(Context context) {
        super(context);
    }

    /** The editable buffer for EditText, plain text otherwise. */
    CharSequence wrap(CharSequence value) {
        return value == null ? "" : value;
    }

    public CharSequence getText() {
        return text;
    }

    public final void setText(CharSequence value) {
        CharSequence old = text;
        CharSequence next = wrap(value);
        int oldLength = old.length();
        for (TextWatcher watcher : List.copyOf(watchers)) watcher.beforeTextChanged(old, 0, oldLength, next.length());
        text = next;
        for (TextWatcher watcher : List.copyOf(watchers)) watcher.onTextChanged(next, 0, oldLength, next.length());
        if (!watchers.isEmpty()) {
            Editable editable = next instanceof Editable e ? e : new SpannableStringBuilder(next);
            for (TextWatcher watcher : List.copyOf(watchers)) watcher.afterTextChanged(editable);
        }
    }

    public final void setText(int resid) {
        setText("");
    }

    public void append(CharSequence more) {
        setText(text.toString() + more);
    }

    public int length() {
        return text.length();
    }

    public void addTextChangedListener(TextWatcher watcher) {
        watchers.add(watcher);
    }

    public void removeTextChangedListener(TextWatcher watcher) {
        watchers.remove(watcher);
    }

    public CharSequence getHint() {
        return hint;
    }

    public final void setHint(CharSequence hint) {
        this.hint = hint;
    }

    public final void setHint(int resid) {}

    public CharSequence getError() {
        return error;
    }

    public void setError(CharSequence error) {
        this.error = error;
    }

    public int getInputType() {
        return inputType;
    }

    public void setInputType(int type) {
        this.inputType = type;
    }

    public void setRawInputType(int type) {
        this.inputType = type;
    }

    public void setHorizontallyScrolling(boolean whether) {}

    public void setSingleLine() {
        maxLines = 1;
    }

    public void setSingleLine(boolean singleLine) {
        maxLines = singleLine ? 1 : Integer.MAX_VALUE;
    }

    public void setMaxLines(int maxLines) {
        this.maxLines = maxLines;
    }

    public int getMaxLines() {
        return maxLines;
    }

    public void setLines(int lines) {
        this.maxLines = lines;
    }

    public void setMinLines(int minLines) {}

    public void setTextColor(int color) {
        this.textColor = color;
    }

    public final int getCurrentTextColor() {
        return textColor;
    }

    public void setTextSize(float size) {
        this.textSize = size;
    }

    public void setTextSize(int unit, float size) {
        this.textSize = size;
    }

    public float getTextSize() {
        return textSize;
    }

    public void setGravity(int gravity) {
        this.gravity = gravity;
    }

    public int getGravity() {
        return gravity;
    }

    public void setFilters(InputFilter[] filters) {
        this.filters = filters.clone();
    }

    public InputFilter[] getFilters() {
        return filters.clone();
    }

    public final void setMovementMethod(MovementMethod movement) {
        this.movementMethod = movement;
    }

    public final MovementMethod getMovementMethod() {
        return movementMethod;
    }

    public void setTextIsSelectable(boolean selectable) {}

    public final void setLinksClickable(boolean whether) {}

    public void setAllCaps(boolean allCaps) {}

    public void setImeOptions(int imeOptions) {}

    public void setSelectAllOnFocus(boolean selectAllOnFocus) {}
}
