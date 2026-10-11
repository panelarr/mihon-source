// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;

/**
 * A single-choice setting. Extends DialogPreference as in androidx (the extensions-lib stub
 * extends Preference directly; both resolve the same calls).
 */
public class ListPreference extends DialogPreference {
    private CharSequence[] entries;
    private CharSequence[] entryValues;
    private String value;

    public ListPreference(Context context) {
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

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    /** The entry (label) for the current value, or null. */
    public CharSequence getEntry() {
        int index = findIndexOfValue(value);
        return index >= 0 && entries != null && index < entries.length ? entries[index] : null;
    }

    public int findIndexOfValue(String value) {
        if (value == null || entryValues == null) return -1;
        for (int i = entryValues.length - 1; i >= 0; i--) {
            if (entryValues[i] != null && entryValues[i].toString().equals(value)) return i;
        }
        return -1;
    }

    public void setValueIndex(int index) {
        if (entryValues != null) setValue(entryValues[index].toString());
    }
}
