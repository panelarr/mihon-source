// SPDX-License-Identifier: GPL-3.0-or-later

package android.webkit;

/** Receives a value asynchronously. */
public interface ValueCallback<T> {
    void onReceiveValue(T value);
}
