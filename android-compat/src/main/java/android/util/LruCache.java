// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A cache that holds at most {@code maxSize} worth of entries (by {@link #sizeOf}, 1 per entry
 * by default) and evicts the least recently used entry first. Thread-safe.
 */
public class LruCache<K, V> {
    private final LinkedHashMap<K, V> map = new LinkedHashMap<>(0, 0.75f, true);
    private int size;
    private int maxSize;
    private int putCount;
    private int createCount;
    private int evictionCount;
    private int hitCount;
    private int missCount;

    public LruCache(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize <= 0");
        this.maxSize = maxSize;
    }

    public void resize(int maxSize) {
        if (maxSize <= 0) throw new IllegalArgumentException("maxSize <= 0");
        synchronized (this) {
            this.maxSize = maxSize;
        }
        trimToSize(maxSize);
    }

    public final V get(K key) {
        if (key == null) throw new NullPointerException("key == null");
        synchronized (this) {
            V value = map.get(key);
            if (value != null) {
                hitCount++;
                return value;
            }
            missCount++;
        }
        V created = create(key);
        if (created == null) return null;
        V previous;
        synchronized (this) {
            createCount++;
            previous = map.put(key, created);
            if (previous != null) map.put(key, previous);
            else size += safeSizeOf(key, created);
        }
        if (previous != null) {
            entryRemoved(false, key, created, previous);
            return previous;
        }
        trimToSize(maxSize);
        return created;
    }

    public final V put(K key, V value) {
        if (key == null || value == null) throw new NullPointerException("key == null || value == null");
        V previous;
        synchronized (this) {
            putCount++;
            size += safeSizeOf(key, value);
            previous = map.put(key, value);
            if (previous != null) size -= safeSizeOf(key, previous);
        }
        if (previous != null) entryRemoved(false, key, previous, value);
        trimToSize(maxSize);
        return previous;
    }

    public void trimToSize(int maxSize) {
        while (true) {
            K key;
            V value;
            synchronized (this) {
                if (size <= maxSize || map.isEmpty()) break;
                Map.Entry<K, V> eldest = map.entrySet().iterator().next();
                key = eldest.getKey();
                value = eldest.getValue();
                map.remove(key);
                size -= safeSizeOf(key, value);
                evictionCount++;
            }
            entryRemoved(true, key, value, null);
        }
    }

    public final V remove(K key) {
        if (key == null) throw new NullPointerException("key == null");
        V previous;
        synchronized (this) {
            previous = map.remove(key);
            if (previous != null) size -= safeSizeOf(key, previous);
        }
        if (previous != null) entryRemoved(false, key, previous, null);
        return previous;
    }

    protected void entryRemoved(boolean evicted, K key, V oldValue, V newValue) {}

    protected V create(K key) {
        return null;
    }

    private int safeSizeOf(K key, V value) {
        int result = sizeOf(key, value);
        if (result < 0) throw new IllegalStateException("Negative size: " + key + "=" + value);
        return result;
    }

    protected int sizeOf(K key, V value) {
        return 1;
    }

    public final void evictAll() {
        trimToSize(-1);
    }

    public final synchronized int size() {
        return size;
    }

    public final synchronized int maxSize() {
        return maxSize;
    }

    public final synchronized int hitCount() {
        return hitCount;
    }

    public final synchronized int missCount() {
        return missCount;
    }

    public final synchronized int createCount() {
        return createCount;
    }

    public final synchronized int putCount() {
        return putCount;
    }

    public final synchronized int evictionCount() {
        return evictionCount;
    }

    public final synchronized Map<K, V> snapshot() {
        return new LinkedHashMap<>(map);
    }

    @Override
    public final synchronized String toString() {
        int accesses = hitCount + missCount;
        int hitPercent = accesses == 0 ? 0 : 100 * hitCount / accesses;
        return "LruCache[maxSize=" + maxSize + ",hits=" + hitCount + ",misses=" + missCount + ",hitRate=" + hitPercent + "%]";
    }
}
