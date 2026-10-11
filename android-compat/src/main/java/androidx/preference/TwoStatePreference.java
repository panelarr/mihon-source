// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;

/** An on/off setting. */
public class TwoStatePreference extends Preference {
    private boolean checked;
    private CharSequence summaryOn;
    private CharSequence summaryOff;
    private boolean disableDependentsState;

    public TwoStatePreference(Context context) {
        super(context);
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    public CharSequence getSummaryOn() {
        return summaryOn;
    }

    public void setSummaryOn(CharSequence summary) {
        this.summaryOn = summary;
    }

    public void setSummaryOn(int summaryResId) {}

    public CharSequence getSummaryOff() {
        return summaryOff;
    }

    public void setSummaryOff(CharSequence summary) {
        this.summaryOff = summary;
    }

    public void setSummaryOff(int summaryResId) {}

    public boolean getDisableDependentsState() {
        return disableDependentsState;
    }

    public void setDisableDependentsState(boolean disableDependentsState) {
        this.disableDependentsState = disableDependentsState;
    }
}
