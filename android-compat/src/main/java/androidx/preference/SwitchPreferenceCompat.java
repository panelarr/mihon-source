// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;

/** An on/off setting shown as a switch. */
public class SwitchPreferenceCompat extends TwoStatePreference {
    private CharSequence switchTextOn;
    private CharSequence switchTextOff;

    public SwitchPreferenceCompat(Context context) {
        super(context);
    }

    public CharSequence getSwitchTextOn() {
        return switchTextOn;
    }

    public void setSwitchTextOn(CharSequence text) {
        this.switchTextOn = text;
    }

    public CharSequence getSwitchTextOff() {
        return switchTextOff;
    }

    public void setSwitchTextOff(CharSequence text) {
        this.switchTextOff = text;
    }
}
