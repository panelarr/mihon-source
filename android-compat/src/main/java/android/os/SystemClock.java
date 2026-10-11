// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Android's SystemClock. "Boot" is the start of this JVM: both clocks are monotonic and never
 * go backwards when the wall clock changes, which is what rate limiters rely on.
 */
public final class SystemClock {
    private static final long ORIGIN_NANOS = System.nanoTime() - ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000L;

    private SystemClock() {}

    public static long elapsedRealtime() {
        return elapsedRealtimeNanos() / 1_000_000L;
    }

    public static long elapsedRealtimeNanos() {
        return System.nanoTime() - ORIGIN_NANOS;
    }

    public static long uptimeMillis() {
        return elapsedRealtime();
    }

    public static long uptimeNanos() {
        return elapsedRealtimeNanos();
    }

    public static long currentThreadTimeMillis() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean.isCurrentThreadCpuTimeSupported()) return bean.getCurrentThreadCpuTime() / 1_000_000L;
        return elapsedRealtime();
    }

    public static boolean setCurrentTimeMillis(long millis) {
        return false;
    }

    /** Sleeps without being interruptible, as on Android; a pending interrupt is restored afterwards. */
    public static void sleep(long ms) {
        long deadline = System.nanoTime() + ms * 1_000_000L;
        boolean interrupted = false;
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
