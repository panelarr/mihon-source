// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

import java.io.IOException;

/** Thrown by {@link JsonReader} on invalid JSON. */
public final class MalformedJsonException extends IOException {
    public MalformedJsonException(String message) {
        super(message);
    }
}
