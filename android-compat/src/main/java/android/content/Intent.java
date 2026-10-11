// SPDX-License-Identifier: GPL-3.0-or-later

package android.content;

import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import java.io.Serializable;
import java.util.ArrayList;

/** Android's Intent as a plain data holder. Nothing ever handles an intent on this host. */
public class Intent implements Parcelable, Cloneable {
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String ACTION_SEND = "android.intent.action.SEND";
    public static final String ACTION_SENDTO = "android.intent.action.SENDTO";
    public static final String CATEGORY_DEFAULT = "android.intent.category.DEFAULT";
    public static final String CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE";
    public static final String EXTRA_TEXT = "android.intent.extra.TEXT";
    public static final String EXTRA_SUBJECT = "android.intent.extra.SUBJECT";
    public static final String EXTRA_TITLE = "android.intent.extra.TITLE";

    public static final int FLAG_ACTIVITY_NO_HISTORY = 0x40000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_CLEAR_TASK = 0x00008000;
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;

    private String action;
    private Uri data;
    private String type;
    private ComponentName component;
    private String packageName;
    private int flags;
    private Bundle extras;
    private final ArrayList<String> categories = new ArrayList<>();

    public Intent() {}

    public Intent(Intent o) {
        action = o.action;
        data = o.data;
        type = o.type;
        component = o.component;
        packageName = o.packageName;
        flags = o.flags;
        extras = o.extras == null ? null : new Bundle(o.extras);
        categories.addAll(o.categories);
    }

    public Intent(String action) {
        this.action = action;
    }

    public Intent(String action, Uri uri) {
        this.action = action;
        this.data = uri;
    }

    public Intent(Context packageContext, Class<?> cls) {
        this.component = new ComponentName(packageContext, cls);
    }

    public Intent(String action, Uri uri, Context packageContext, Class<?> cls) {
        this(packageContext, cls);
        this.action = action;
        this.data = uri;
    }

    public static Intent createChooser(Intent target, CharSequence title) {
        return new Intent(target);
    }

    @Override
    public Object clone() {
        return new Intent(this);
    }

    public String getAction() {
        return action;
    }

    public Intent setAction(String action) {
        this.action = action;
        return this;
    }

    public Uri getData() {
        return data;
    }

    public String getDataString() {
        return data == null ? null : data.toString();
    }

    public Intent setData(Uri data) {
        this.data = data;
        this.type = null;
        return this;
    }

    public String getType() {
        return type;
    }

    public Intent setType(String type) {
        this.type = type;
        this.data = null;
        return this;
    }

    public Intent setDataAndType(Uri data, String type) {
        this.data = data;
        this.type = type;
        return this;
    }

    public ComponentName getComponent() {
        return component;
    }

    public Intent setComponent(ComponentName component) {
        this.component = component;
        return this;
    }

    public Intent setClassName(String packageName, String className) {
        this.component = new ComponentName(packageName, className);
        return this;
    }

    public Intent setClassName(Context packageContext, String className) {
        this.component = new ComponentName(packageContext, className);
        return this;
    }

    public Intent setClass(Context packageContext, Class<?> cls) {
        this.component = new ComponentName(packageContext, cls);
        return this;
    }

    public String getPackage() {
        return packageName;
    }

    public Intent setPackage(String packageName) {
        this.packageName = packageName;
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public Intent setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public Intent addFlags(int flags) {
        this.flags |= flags;
        return this;
    }

    public void removeFlags(int flags) {
        this.flags &= ~flags;
    }

    public Intent addCategory(String category) {
        categories.add(category);
        return this;
    }

    public boolean hasCategory(String category) {
        return categories.contains(category);
    }

    public java.util.Set<String> getCategories() {
        return categories.isEmpty() ? null : new java.util.LinkedHashSet<>(categories);
    }

    private Bundle extras() {
        if (extras == null) extras = new Bundle();
        return extras;
    }

    public Bundle getExtras() {
        return extras == null ? null : new Bundle(extras);
    }

    public boolean hasExtra(String name) {
        return extras != null && extras.containsKey(name);
    }

    public Intent putExtras(Bundle bundle) {
        extras().putAll(bundle);
        return this;
    }

    public Intent putExtras(Intent src) {
        if (src.extras != null) extras().putAll(src.extras);
        return this;
    }

    public void removeExtra(String name) {
        if (extras != null) extras.remove(name);
    }

    public Intent putExtra(String name, String value) {
        extras().putString(name, value);
        return this;
    }

    public Intent putExtra(String name, CharSequence value) {
        extras().putCharSequence(name, value);
        return this;
    }

    public Intent putExtra(String name, boolean value) {
        extras().putBoolean(name, value);
        return this;
    }

    public Intent putExtra(String name, int value) {
        extras().putInt(name, value);
        return this;
    }

    public Intent putExtra(String name, long value) {
        extras().putLong(name, value);
        return this;
    }

    public Intent putExtra(String name, float value) {
        extras().putFloat(name, value);
        return this;
    }

    public Intent putExtra(String name, double value) {
        extras().putDouble(name, value);
        return this;
    }

    public Intent putExtra(String name, String[] value) {
        extras().putStringArray(name, value);
        return this;
    }

    public Intent putExtra(String name, Parcelable value) {
        extras().putParcelable(name, value);
        return this;
    }

    public Intent putExtra(String name, Serializable value) {
        extras().putSerializable(name, value);
        return this;
    }

    public Intent putExtra(String name, Bundle value) {
        extras().putBundle(name, value);
        return this;
    }

    public Intent putStringArrayListExtra(String name, ArrayList<String> value) {
        extras().putStringArrayList(name, value);
        return this;
    }

    public String getStringExtra(String name) {
        return extras == null ? null : extras.getString(name);
    }

    public CharSequence getCharSequenceExtra(String name) {
        return extras == null ? null : extras.getCharSequence(name);
    }

    public boolean getBooleanExtra(String name, boolean defaultValue) {
        return extras == null ? defaultValue : extras.getBoolean(name, defaultValue);
    }

    public int getIntExtra(String name, int defaultValue) {
        return extras == null ? defaultValue : extras.getInt(name, defaultValue);
    }

    public long getLongExtra(String name, long defaultValue) {
        return extras == null ? defaultValue : extras.getLong(name, defaultValue);
    }

    public float getFloatExtra(String name, float defaultValue) {
        return extras == null ? defaultValue : extras.getFloat(name, defaultValue);
    }

    public double getDoubleExtra(String name, double defaultValue) {
        return extras == null ? defaultValue : extras.getDouble(name, defaultValue);
    }

    public String[] getStringArrayExtra(String name) {
        return extras == null ? null : extras.getStringArray(name);
    }

    public ArrayList<String> getStringArrayListExtra(String name) {
        return extras == null ? null : extras.getStringArrayList(name);
    }

    public <T extends Parcelable> T getParcelableExtra(String name) {
        return extras == null ? null : extras.getParcelable(name);
    }

    public <T> T getParcelableExtra(String name, Class<T> clazz) {
        return extras == null ? null : extras.getParcelable(name, clazz);
    }

    public Serializable getSerializableExtra(String name) {
        return extras == null ? null : extras.getSerializable(name);
    }

    public Bundle getBundleExtra(String name) {
        return extras == null ? null : extras.getBundle(name);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(android.os.Parcel dest, int flags) {}

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Intent {");
        if (action != null) sb.append(" act=").append(action);
        if (data != null) sb.append(" dat=").append(data);
        if (type != null) sb.append(" typ=").append(type);
        if (component != null) sb.append(" cmp=").append(component.flattenToShortString());
        if (extras != null) sb.append(" (has extras)");
        return sb.append(" }").toString();
    }
}
