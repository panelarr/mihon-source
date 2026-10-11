// SPDX-License-Identifier: GPL-3.0-or-later

package android.os;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Android's Handler. Every handler runs its work on the main looper's thread, the only looper
 * on this host. Pending work can be removed by runnable, message code or token.
 */
public class Handler {
    public interface Callback {
        boolean handleMessage(Message msg);
    }

    private final Looper looper;
    private final Callback callback;
    private final List<Pending> pending = new ArrayList<>();

    private record Pending(Runnable runnable, Message message, Object token, ScheduledFuture<?>[] future) {}

    public Handler() {
        this(null, null);
    }

    public Handler(Callback callback) {
        this(null, callback);
    }

    public Handler(Looper looper) {
        this(looper, null);
    }

    public Handler(Looper looper, Callback callback) {
        Looper mine = Looper.myLooper();
        this.looper = looper != null ? looper : mine != null ? mine : Looper.getMainLooper();
        this.callback = callback;
    }

    public static Handler createAsync(Looper looper) {
        return new Handler(looper);
    }

    public static Handler createAsync(Looper looper, Callback callback) {
        return new Handler(looper, callback);
    }

    public final Looper getLooper() {
        return looper;
    }

    /** Subclasses override this to receive messages. */
    public void handleMessage(Message msg) {}

    public void dispatchMessage(Message msg) {
        if (msg.callback != null) {
            msg.callback.run();
        } else if (callback == null || !callback.handleMessage(msg)) {
            handleMessage(msg);
        }
    }

    private boolean enqueue(Runnable runnable, Message message, Object token, long delayMillis) {
        ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        Pending entry = new Pending(runnable, message, token, holder);
        synchronized (pending) {
            pending.add(entry);
            holder[0] = looper.schedule(() -> {
                synchronized (pending) {
                    if (!pending.remove(entry)) return;
                }
                if (message != null) dispatchMessage(message);
                else runnable.run();
            }, delayMillis);
        }
        return true;
    }

    private void removeMatching(java.util.function.Predicate<Pending> matcher) {
        synchronized (pending) {
            pending.removeIf(entry -> {
                if (!matcher.test(entry)) return false;
                if (entry.future[0] != null) entry.future[0].cancel(false);
                return true;
            });
        }
    }

    public final boolean post(Runnable r) {
        return enqueue(r, null, null, 0);
    }

    public final boolean postDelayed(Runnable r, long delayMillis) {
        return enqueue(r, null, null, delayMillis);
    }

    public final boolean postDelayed(Runnable r, Object token, long delayMillis) {
        return enqueue(r, null, token, delayMillis);
    }

    public final boolean postAtTime(Runnable r, long uptimeMillis) {
        return enqueue(r, null, null, uptimeMillis - SystemClock.uptimeMillis());
    }

    public final boolean postAtTime(Runnable r, Object token, long uptimeMillis) {
        return enqueue(r, null, token, uptimeMillis - SystemClock.uptimeMillis());
    }

    public final boolean postAtFrontOfQueue(Runnable r) {
        return enqueue(r, null, null, 0);
    }

    public final void removeCallbacks(Runnable r) {
        removeMatching(entry -> entry.runnable == r);
    }

    public final void removeCallbacks(Runnable r, Object token) {
        removeMatching(entry -> entry.runnable == r && (token == null || entry.token == token));
    }

    public final void removeCallbacksAndMessages(Object token) {
        removeMatching(entry -> token == null || entry.token == token
            || (entry.message != null && entry.message.obj == token));
    }

    public final boolean hasCallbacks(Runnable r) {
        synchronized (pending) {
            return pending.stream().anyMatch(entry -> entry.runnable == r);
        }
    }

    public final Message obtainMessage() {
        return Message.obtain(this);
    }

    public final Message obtainMessage(int what) {
        return Message.obtain(this, what);
    }

    public final Message obtainMessage(int what, Object obj) {
        return Message.obtain(this, what, obj);
    }

    public final Message obtainMessage(int what, int arg1, int arg2) {
        return Message.obtain(this, what, arg1, arg2);
    }

    public final Message obtainMessage(int what, int arg1, int arg2, Object obj) {
        return Message.obtain(this, what, arg1, arg2, obj);
    }

    public final boolean sendMessage(Message msg) {
        return sendMessageDelayed(msg, 0);
    }

    public final boolean sendMessageDelayed(Message msg, long delayMillis) {
        msg.target = this;
        return enqueue(null, msg, null, delayMillis);
    }

    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        return sendMessageDelayed(msg, uptimeMillis - SystemClock.uptimeMillis());
    }

    public final boolean sendMessageAtFrontOfQueue(Message msg) {
        return sendMessage(msg);
    }

    public final boolean sendEmptyMessage(int what) {
        return sendMessage(obtainMessage(what));
    }

    public final boolean sendEmptyMessageDelayed(int what, long delayMillis) {
        return sendMessageDelayed(obtainMessage(what), delayMillis);
    }

    public final void removeMessages(int what) {
        removeMatching(entry -> entry.message != null && entry.message.what == what);
    }

    public final void removeMessages(int what, Object object) {
        removeMatching(entry -> entry.message != null && entry.message.what == what
            && (object == null || entry.message.obj == object));
    }

    public final boolean hasMessages(int what) {
        synchronized (pending) {
            return pending.stream().anyMatch(entry -> entry.message != null && entry.message.what == what);
        }
    }

    @Override
    public String toString() {
        return "Handler (" + getClass().getName() + ") {" + Integer.toHexString(System.identityHashCode(this)) + "}";
    }
}
