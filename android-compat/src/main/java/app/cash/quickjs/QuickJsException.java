// SPDX-License-Identifier: GPL-3.0-or-later

package app.cash.quickjs;

/** A JavaScript error, or a failure of the script engine itself. */
public final class QuickJsException extends RuntimeException {
    private final String jsStackTrace;

    public QuickJsException(String message) {
        this(message, (String) null);
    }

    public QuickJsException(String message, String jsStackTrace) {
        super(message);
        this.jsStackTrace = jsStackTrace;
    }

    public QuickJsException(String message, Throwable cause) {
        super(message, cause);
        this.jsStackTrace = null;
    }

    /** The JavaScript stack trace, when there is one. */
    public String getJsStackTrace() {
        return jsStackTrace;
    }
}
