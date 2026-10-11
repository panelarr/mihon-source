// SPDX-License-Identifier: GPL-3.0-or-later

package android.content;

import java.util.Objects;

/** Identifies an Android component by package and class name. Data only. */
public final class ComponentName implements android.os.Parcelable, Comparable<ComponentName>, Cloneable {
    private final String packageName;
    private final String className;

    public ComponentName(String pkg, String cls) {
        this.packageName = Objects.requireNonNull(pkg, "package name is null");
        this.className = Objects.requireNonNull(cls, "class name is null");
    }

    public ComponentName(Context pkg, String cls) {
        this(pkg.getPackageName(), cls);
    }

    public ComponentName(Context pkg, Class<?> cls) {
        this(pkg.getPackageName(), cls.getName());
    }

    public String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }

    public String getShortClassName() {
        return className.startsWith(packageName + ".") ? className.substring(packageName.length()) : className;
    }

    public String flattenToString() {
        return packageName + "/" + className;
    }

    public String flattenToShortString() {
        return packageName + "/" + getShortClassName();
    }

    public static ComponentName unflattenFromString(String str) {
        int sep = str.indexOf('/');
        if (sep < 0 || sep + 1 >= str.length()) return null;
        String pkg = str.substring(0, sep);
        String cls = str.substring(sep + 1);
        if (cls.charAt(0) == '.') cls = pkg + cls;
        return new ComponentName(pkg, cls);
    }

    @Override
    public ComponentName clone() {
        return new ComponentName(packageName, className);
    }

    @Override
    public int compareTo(ComponentName that) {
        int result = packageName.compareTo(that.packageName);
        return result != 0 ? result : className.compareTo(that.className);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ComponentName other
            && packageName.equals(other.packageName)
            && className.equals(other.className);
    }

    @Override
    public int hashCode() {
        return packageName.hashCode() + className.hashCode();
    }

    @Override
    public String toString() {
        return "ComponentInfo{" + packageName + "/" + className + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(android.os.Parcel dest, int flags) {}
}
