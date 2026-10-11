// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;
import android.widget.EditText;

/** A text setting. */
public class EditTextPreference extends DialogPreference {
    private String text;
    private OnBindEditTextListener onBindEditTextListener;

    public interface OnBindEditTextListener {
        void onBindEditText(EditText editText);
    }

    public EditTextPreference(Context context) {
        super(context);
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public OnBindEditTextListener getOnBindEditTextListener() {
        return onBindEditTextListener;
    }

    public void setOnBindEditTextListener(OnBindEditTextListener listener) {
        this.onBindEditTextListener = listener;
    }
}
