// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import panelarr.mihonsource.compat.SourceCaller;

/**
 * Android's Looper. There is exactly one: the main looper, a single daemon thread named
 * "main" that runs everything posted through {@link Handler} and {@code Dispatchers.Main}.
 * It starts on first use. {@link #prepare()} and {@link #loop()} are no-ops, so other threads
 * have no looper of their own.
 */
public final class Looper {
    private static final Logger LOG = Logger.getLogger(Looper.class.getName());

    private final ScheduledThreadPoolExecutor executor;
    private final Thread thread;

    private Looper() {
        Thread[] created = new Thread[1];
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread t = new Thread(runnable, "main");
            t.setDaemon(true);
            created[0] = t;
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.prestartAllCoreThreads();
        thread = created[0];
    }

    private static final class Main {
        static final Looper LOOPER = new Looper();
    }

    public static Looper getMainLooper() {
        return Main.LOOPER;
    }

    /** The main looper when called on the main thread, otherwise null (as on Android). */
    public static Looper myLooper() {
        Looper main = Main.LOOPER;
        return Thread.currentThread() == main.thread ? main : null;
    }

    public static void prepare() {}

    public static void prepareMainLooper() {}

    public static void loop() {}

    public void quit() {}

    public void quitSafely() {}

    public Thread getThread() {
        return thread;
    }

    public boolean isCurrentThread() {
        return Thread.currentThread() == thread;
    }

    /** Runs {@code task} later on the main thread, as working for the source of the thread that scheduled it. */
    ScheduledFuture<?> schedule(Runnable task, long delayMillis) {
        Runnable bound = SourceCaller.bind(task);
        Runnable guarded = () -> {
            try {
                bound.run();
            } catch (Throwable t) {
                // On Android this would crash the app; here we log and keep the thread alive.
                LOG.log(Level.SEVERE, "Uncaught exception on the main looper", t);
            }
        };
        return executor.schedule(guarded, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    @Override
    public String toString() {
        return "Looper (main, tid " + thread.threadId() + ")";
    }
}
