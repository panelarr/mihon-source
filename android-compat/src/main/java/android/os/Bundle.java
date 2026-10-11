// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Android's Bundle: a string-keyed map of values with typed getters. */
public final class Bundle implements Parcelable, Cloneable {
    public static final Bundle EMPTY = new Bundle();

    private final Map<String, Object> map = new LinkedHashMap<>();

    public Bundle() {}

    public Bundle(Bundle b) {
        map.putAll(b.map);
    }

    public Bundle(ClassLoader loader) {}

    public Bundle(int capacity) {}

    @Override
    public Object clone() {
        return new Bundle(this);
    }

    public Bundle deepCopy() {
        return new Bundle(this);
    }

    public void setClassLoader(ClassLoader loader) {}

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public void clear() {
        map.clear();
    }

    public boolean containsKey(String key) {
        return map.containsKey(key);
    }

    public Object get(String key) {
        return map.get(key);
    }

    public void remove(String key) {
        map.remove(key);
    }

    public void putAll(Bundle bundle) {
        map.putAll(bundle.map);
    }

    public Set<String> keySet() {
        return map.keySet();
    }

    @SuppressWarnings("unchecked")
    private <T> T typed(String key, Class<T> type) {
        Object value = map.get(key);
        // Android logs and returns the default on a type mismatch rather than throwing.
        return type.isInstance(value) ? (T) value : null;
    }

    public void putBoolean(String key, boolean value) {
        map.put(key, value);
    }

    public void putByte(String key, byte value) {
        map.put(key, value);
    }

    public void putChar(String key, char value) {
        map.put(key, value);
    }

    public void putShort(String key, short value) {
        map.put(key, value);
    }

    public void putInt(String key, int value) {
        map.put(key, value);
    }

    public void putLong(String key, long value) {
        map.put(key, value);
    }

    public void putFloat(String key, float value) {
        map.put(key, value);
    }

    public void putDouble(String key, double value) {
        map.put(key, value);
    }

    public void putString(String key, String value) {
        map.put(key, value);
    }

    public void putCharSequence(String key, CharSequence value) {
        map.put(key, value);
    }

    public void putParcelable(String key, Parcelable value) {
        map.put(key, value);
    }

    public void putSerializable(String key, Serializable value) {
        map.put(key, value);
    }

    public void putBundle(String key, Bundle value) {
        map.put(key, value);
    }

    public void putStringArray(String key, String[] value) {
        map.put(key, value);
    }

    public void putIntArray(String key, int[] value) {
        map.put(key, value);
    }

    public void putLongArray(String key, long[] value) {
        map.put(key, value);
    }

    public void putByteArray(String key, byte[] value) {
        map.put(key, value);
    }

    public void putBooleanArray(String key, boolean[] value) {
        map.put(key, value);
    }

    public void putStringArrayList(String key, ArrayList<String> value) {
        map.put(key, value);
    }

    public void putIntegerArrayList(String key, ArrayList<Integer> value) {
        map.put(key, value);
    }

    public void putParcelableArrayList(String key, ArrayList<? extends Parcelable> value) {
        map.put(key, value);
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Boolean value = typed(key, Boolean.class);
        return value == null ? defaultValue : value;
    }

    public byte getByte(String key) {
        return getByte(key, (byte) 0);
    }

    public Byte getByte(String key, byte defaultValue) {
        Byte value = typed(key, Byte.class);
        return value == null ? defaultValue : value;
    }

    public char getChar(String key) {
        return getChar(key, (char) 0);
    }

    public char getChar(String key, char defaultValue) {
        Character value = typed(key, Character.class);
        return value == null ? defaultValue : value;
    }

    public short getShort(String key) {
        return getShort(key, (short) 0);
    }

    public short getShort(String key, short defaultValue) {
        Short value = typed(key, Short.class);
        return value == null ? defaultValue : value;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        Integer value = typed(key, Integer.class);
        return value == null ? defaultValue : value;
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long defaultValue) {
        Long value = typed(key, Long.class);
        return value == null ? defaultValue : value;
    }

    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public float getFloat(String key, float defaultValue) {
        Float value = typed(key, Float.class);
        return value == null ? defaultValue : value;
    }

    public double getDouble(String key) {
        return getDouble(key, 0.0);
    }

    public double getDouble(String key, double defaultValue) {
        Double value = typed(key, Double.class);
        return value == null ? defaultValue : value;
    }

    public String getString(String key) {
        return typed(key, String.class);
    }

    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : value;
    }

    public CharSequence getCharSequence(String key) {
        return typed(key, CharSequence.class);
    }

    public CharSequence getCharSequence(String key, CharSequence defaultValue) {
        CharSequence value = getCharSequence(key);
        return value == null ? defaultValue : value;
    }

    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T getParcelable(String key) {
        return (T) typed(key, Parcelable.class);
    }

    public <T> T getParcelable(String key, Class<T> clazz) {
        return typed(key, clazz);
    }

    public Serializable getSerializable(String key) {
        return typed(key, Serializable.class);
    }

    public <T extends Serializable> T getSerializable(String key, Class<T> clazz) {
        return typed(key, clazz);
    }

    public Bundle getBundle(String key) {
        return typed(key, Bundle.class);
    }

    public String[] getStringArray(String key) {
        return typed(key, String[].class);
    }

    public int[] getIntArray(String key) {
        return typed(key, int[].class);
    }

    public long[] getLongArray(String key) {
        return typed(key, long[].class);
    }

    public byte[] getByteArray(String key) {
        return typed(key, byte[].class);
    }

    public boolean[] getBooleanArray(String key) {
        return typed(key, boolean[].class);
    }

    @SuppressWarnings("unchecked")
    public ArrayList<String> getStringArrayList(String key) {
        return typed(key, ArrayList.class);
    }

    @SuppressWarnings("unchecked")
    public ArrayList<Integer> getIntegerArrayList(String key) {
        return typed(key, ArrayList.class);
    }

    @SuppressWarnings("unchecked")
    public <T extends Parcelable> ArrayList<T> getParcelableArrayList(String key) {
        return typed(key, ArrayList.class);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(this);
    }

    @Override
    public String toString() {
        return "Bundle" + map;
    }
}
