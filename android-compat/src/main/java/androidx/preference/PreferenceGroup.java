// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A preference that contains other preferences, in insertion order. */
public abstract class PreferenceGroup extends Preference {
    private final List<Preference> preferences = new ArrayList<>();

    public PreferenceGroup() {
        super();
    }

    public PreferenceGroup(Context context) {
        super(context);
    }

    public boolean addPreference(Preference preference) {
        synchronized (preferences) {
            preferences.add(preference);
        }
        return true;
    }

    public boolean removePreference(Preference preference) {
        synchronized (preferences) {
            return preferences.remove(preference);
        }
    }

    public void removeAll() {
        synchronized (preferences) {
            preferences.clear();
        }
    }

    public int getPreferenceCount() {
        synchronized (preferences) {
            return preferences.size();
        }
    }

    public Preference getPreference(int index) {
        synchronized (preferences) {
            return preferences.get(index);
        }
    }

    /** A snapshot of the direct children, in insertion order. */
    public List<Preference> getPreferences() {
        synchronized (preferences) {
            return Collections.unmodifiableList(new ArrayList<>(preferences));
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends Preference> T findPreference(CharSequence key) {
        if (key == null) return null;
        for (Preference preference : getPreferences()) {
            if (key.toString().equals(preference.getKey())) return (T) preference;
            if (preference instanceof PreferenceGroup group) {
                T found = group.findPreference(key);
                if (found != null) return found;
            }
        }
        return null;
    }
}
