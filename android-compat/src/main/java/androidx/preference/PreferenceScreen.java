// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;

/**
 * The root an extension adds its preferences to. The no-argument constructor (the
 * extensions-lib stub's) uses the host application as the context.
 */
public class PreferenceScreen extends PreferenceGroup {
    public PreferenceScreen() {
        super();
    }

    public PreferenceScreen(Context context) {
        super(context);
    }
}
