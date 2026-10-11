// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Android's logging API, forwarded to java.util.logging under "extension.<tag>". */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private Log() {}

    private static int log(Level level, String tag, String msg, Throwable tr) {
        Logger.getLogger("extension." + tag).log(level, msg, tr);
        return msg == null ? 0 : msg.length();
    }

    public static int v(String tag, String msg) { return log(Level.FINEST, tag, msg, null); }

    public static int v(String tag, String msg, Throwable tr) { return log(Level.FINEST, tag, msg, tr); }

    public static int d(String tag, String msg) { return log(Level.FINE, tag, msg, null); }

    public static int d(String tag, String msg, Throwable tr) { return log(Level.FINE, tag, msg, tr); }

    public static int i(String tag, String msg) { return log(Level.INFO, tag, msg, null); }

    public static int i(String tag, String msg, Throwable tr) { return log(Level.INFO, tag, msg, tr); }

    public static int w(String tag, String msg) { return log(Level.WARNING, tag, msg, null); }

    public static int w(String tag, String msg, Throwable tr) { return log(Level.WARNING, tag, msg, tr); }

    public static int w(String tag, Throwable tr) { return log(Level.WARNING, tag, "", tr); }

    public static int e(String tag, String msg) { return log(Level.SEVERE, tag, msg, null); }

    public static int e(String tag, String msg, Throwable tr) { return log(Level.SEVERE, tag, msg, tr); }

    public static int wtf(String tag, String msg) { return log(Level.SEVERE, tag, msg, null); }

    public static int wtf(String tag, Throwable tr) { return log(Level.SEVERE, tag, "", tr); }

    public static int wtf(String tag, String msg, Throwable tr) { return log(Level.SEVERE, tag, msg, tr); }

    public static boolean isLoggable(String tag, int level) { return level >= INFO; }

    public static int println(int priority, String tag, String msg) {
        Level level = priority >= ERROR ? Level.SEVERE : priority == WARN ? Level.WARNING : priority == INFO ? Level.INFO : Level.FINE;
        return log(level, tag, msg, null);
    }

    public static String getStackTraceString(Throwable tr) {
        if (tr == null) return "";
        java.io.StringWriter writer = new java.io.StringWriter();
        tr.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
