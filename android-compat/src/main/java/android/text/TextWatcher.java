// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

/** Listens to changes of a text view's content. */
public interface TextWatcher extends NoCopySpan {
    void beforeTextChanged(CharSequence s, int start, int count, int after);

    void onTextChanged(CharSequence s, int start, int before, int count);

    void afterTextChanged(Editable s);
}
