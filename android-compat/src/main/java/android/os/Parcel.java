// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

import java.util.ArrayDeque;

/**
 * A minimal in-memory Parcel: values are read back in the order they were written. Enough for
 * code that round-trips its own Parcelables; there is no binder or marshalling here.
 */
public final class Parcel {
    private final ArrayDeque<Object> values = new ArrayDeque<>();

    private Parcel() {}

    public static Parcel obtain() {
        return new Parcel();
    }

    public void recycle() {
        values.clear();
    }

    public int dataSize() {
        return values.size();
    }

    public void writeInt(int val) {
        values.add(val);
    }

    public void writeLong(long val) {
        values.add(val);
    }

    public void writeFloat(float val) {
        values.add(val);
    }

    public void writeDouble(double val) {
        values.add(val);
    }

    public void writeByte(byte val) {
        values.add(val);
    }

    public void writeString(String val) {
        values.add(val == null ? NULL : val);
    }

    public void writeValue(Object val) {
        values.add(val == null ? NULL : val);
    }

    public void writeParcelable(Parcelable p, int flags) {
        values.add(p == null ? NULL : p);
    }

    public void writeBundle(Bundle val) {
        values.add(val == null ? NULL : val);
    }

    public int readInt() {
        return (Integer) values.poll();
    }

    public long readLong() {
        return (Long) values.poll();
    }

    public float readFloat() {
        return (Float) values.poll();
    }

    public double readDouble() {
        return (Double) values.poll();
    }

    public byte readByte() {
        return (Byte) values.poll();
    }

    public String readString() {
        return (String) unwrap(values.poll());
    }

    public Object readValue(ClassLoader loader) {
        return unwrap(values.poll());
    }

    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T readParcelable(ClassLoader loader) {
        return (T) unwrap(values.poll());
    }

    public Bundle readBundle() {
        return (Bundle) unwrap(values.poll());
    }

    public Bundle readBundle(ClassLoader loader) {
        return readBundle();
    }

    private static final Object NULL = new Object();

    private static Object unwrap(Object value) {
        return value == NULL ? null : value;
    }
}
