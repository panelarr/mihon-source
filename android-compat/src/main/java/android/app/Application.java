// SPDX-License-Identifier: GPL-3.0-or-later

package android.app;

import android.content.ContextWrapper;
import android.os.Bundle;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Android's Application. The host's single instance is
 * {@code panelarr.mihonsource.compat.AndroidHost.application}; its Context methods are
 * inherited from {@link ContextWrapper}.
 */
public class Application extends ContextWrapper {
    private final List<ActivityLifecycleCallbacks> lifecycleCallbacks = new CopyOnWriteArrayList<>();

    public Application() {
        super(null);
    }

    public static String getProcessName() {
        return "app.mihonsource";
    }

    public void onCreate() {}

    public void onTerminate() {}

    public void onLowMemory() {}

    public void onTrimMemory(int level) {}

    /** Stored only: no activity ever starts on this host, so the callbacks never fire. */
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        lifecycleCallbacks.add(callback);
    }

    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        lifecycleCallbacks.remove(callback);
    }

    public interface ActivityLifecycleCallbacks {
        void onActivityCreated(Activity activity, Bundle savedInstanceState);

        void onActivityStarted(Activity activity);

        void onActivityResumed(Activity activity);

        void onActivityPaused(Activity activity);

        void onActivityStopped(Activity activity);

        void onActivitySaveInstanceState(Activity activity, Bundle outState);

        void onActivityDestroyed(Activity activity);

        default void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {}

        default void onActivityPostCreated(Activity activity, Bundle savedInstanceState) {}

        default void onActivityPreDestroyed(Activity activity) {}

        default void onActivityPostDestroyed(Activity activity) {}
    }
}
