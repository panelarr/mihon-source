// SPDX-License-Identifier: GPL-3.0-or-later

package androidx.preference;

import android.content.Context;
import panelarr.mihonsource.compat.AndroidHost;

/**
 * A setting an extension declares in {@code setupPreferenceScreen}. On this host preferences
 * are plain data holders: every setter stores its value and every getter returns it. The host
 * reads them back to describe and validate settings, and persists values itself.
 */
public class Preference {
    private final Context context;
    private String key;
    private CharSequence title;
    private CharSequence summary;
    private Object defaultValue;
    private boolean enabled = true;
    private boolean visible = true;
    private boolean persistent = true;
    private boolean iconSpaceReserved = true;
    private boolean singleLineTitle = true;
    private int order = Integer.MAX_VALUE;
    private String dependency;
    private OnPreferenceChangeListener onPreferenceChangeListener;
    private OnPreferenceClickListener onPreferenceClickListener;

    public interface OnPreferenceChangeListener {
        boolean onPreferenceChange(Preference preference, Object newValue);
    }

    public interface OnPreferenceClickListener {
        boolean onPreferenceClick(Preference preference);
    }

    /** The extensions-lib stub's constructor. The context is the host application. */
    public Preference() {
        this(AndroidHost.INSTANCE.getApplication());
    }

    public Preference(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public boolean hasKey() {
        return key != null && !key.isEmpty();
    }

    public CharSequence getTitle() {
        return title;
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public void setTitle(int titleResId) {}

    public CharSequence getSummary() {
        return summary;
    }

    public void setSummary(CharSequence summary) {
        this.summary = summary;
    }

    public void setSummary(int summaryResId) {}

    /** Not public in androidx; public here so the host can read the declared default. */
    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public boolean isIconSpaceReserved() {
        return iconSpaceReserved;
    }

    public void setIconSpaceReserved(boolean iconSpaceReserved) {
        this.iconSpaceReserved = iconSpaceReserved;
    }

    public boolean isSingleLineTitle() {
        return singleLineTitle;
    }

    public void setSingleLineTitle(boolean singleLineTitle) {
        this.singleLineTitle = singleLineTitle;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public String getDependency() {
        return dependency;
    }

    public void setDependency(String dependencyKey) {
        this.dependency = dependencyKey;
    }

    public OnPreferenceChangeListener getOnPreferenceChangeListener() {
        return onPreferenceChangeListener;
    }

    public void setOnPreferenceChangeListener(OnPreferenceChangeListener listener) {
        this.onPreferenceChangeListener = listener;
    }

    public OnPreferenceClickListener getOnPreferenceClickListener() {
        return onPreferenceClickListener;
    }

    public void setOnPreferenceClickListener(OnPreferenceClickListener listener) {
        this.onPreferenceClickListener = listener;
    }

    /**
     * Asks the change listener whether {@code newValue} may be saved. Returns true when there
     * is no listener, as on Android.
     */
    public boolean callChangeListener(Object newValue) {
        return onPreferenceChangeListener == null || onPreferenceChangeListener.onPreferenceChange(this, newValue);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{key=" + key + ", title=" + title + "}";
    }
}
