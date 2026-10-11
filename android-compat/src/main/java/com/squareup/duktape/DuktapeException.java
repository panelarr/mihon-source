// SPDX-License-Identifier: GPL-3.0-or-later

package com.squareup.duktape;

/** A JavaScript error, or a failure of the script engine itself. */
public final class DuktapeException extends RuntimeException {
    public DuktapeException(String detailMessage) {
        super(detailMessage);
    }

    public DuktapeException(String detailMessage, Throwable cause) {
        super(detailMessage, cause);
    }
}
