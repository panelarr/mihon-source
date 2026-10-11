// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;
import java.util.HashSet;
import java.util.Set;

/** A multiple-choice setting. Its value is a set of entry values. */
public class MultiSelectListPreference extends DialogPreference {
    private CharSequence[] entries;
    private CharSequence[] entryValues;
    private final Set<String> values = new HashSet<>();

    public MultiSelectListPreference(Context context) {
        super(context);
    }

    public CharSequence[] getEntries() {
        return entries;
    }

    public void setEntries(CharSequence[] entries) {
        this.entries = entries;
    }

    public void setEntries(int entriesResId) {}

    public CharSequence[] getEntryValues() {
        return entryValues;
    }

    public void setEntryValues(CharSequence[] entryValues) {
        this.entryValues = entryValues;
    }

    public void setEntryValues(int entryValuesResId) {}

    public Set<String> getValues() {
        return values;
    }

    public void setValues(Set<String> values) {
        this.values.clear();
        if (values != null) this.values.addAll(values);
    }

    public int findIndexOfValue(String value) {
        if (value == null || entryValues == null) return -1;
        for (int i = entryValues.length - 1; i >= 0; i--) {
            if (entryValues[i] != null && entryValues[i].toString().equals(value)) return i;
        }
        return -1;
    }

    public boolean[] getSelectedItems() {
        CharSequence[] all = entryValues == null ? new CharSequence[0] : entryValues;
        boolean[] selected = new boolean[all.length];
        for (int i = 0; i < all.length; i++) selected[i] = values.contains(String.valueOf(all[i]));
        return selected;
    }
}
