// SPDX-License-Identifier: GPL-3.0-or-later

package android.preference;

import android.content.Context;
import android.content.SharedPreferences;
import panelarr.mihonsource.compat.AndroidHost;

/** The framework PreferenceManager: only the default preferences lookup. */
public class PreferenceManager {
    private PreferenceManager() {}

    /** The host-wide "default" preferences; the context is ignored. */
    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return AndroidHost.INSTANCE.sharedPreferences("default");
    }

    public static String getDefaultSharedPreferencesName(Context context) {
        return "default";
    }
}
