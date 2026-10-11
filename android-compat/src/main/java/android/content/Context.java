// SPDX-License-Identifier: GPL-3.0-or-later

package android.content;

import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import java.io.File;

/**
 * Android's abstract Context. Only the members extensions use are declared; the host's
 * implementation lives behind {@link android.app.Application}.
 */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public static final int MODE_WORLD_READABLE = 1;
    public static final int MODE_WORLD_WRITEABLE = 2;
    public static final int MODE_APPEND = 0x8000;
    public static final int MODE_MULTI_PROCESS = 4;

    public static final String ACTIVITY_SERVICE = "activity";
    public static final String CLIPBOARD_SERVICE = "clipboard";
    public static final String CONNECTIVITY_SERVICE = "connectivity";
    public static final String INPUT_METHOD_SERVICE = "input_method";
    public static final String LAYOUT_INFLATER_SERVICE = "layout_inflater";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final String POWER_SERVICE = "power";
    public static final String WINDOW_SERVICE = "window";

    public Context() {}

    public abstract SharedPreferences getSharedPreferences(String name, int mode);

    public abstract boolean deleteSharedPreferences(String name);

    public abstract File getCacheDir();

    public abstract File getExternalCacheDir();

    public abstract File getCodeCacheDir();

    public abstract File getFilesDir();

    public abstract File getNoBackupFilesDir();

    public abstract File getExternalFilesDir(String type);

    public abstract File getDataDir();

    public abstract File getDir(String name, int mode);

    public abstract String getPackageName();

    public abstract Context getApplicationContext();

    public abstract ApplicationInfo getApplicationInfo();

    public abstract Object getSystemService(String name);

    public abstract Resources getResources();

    public abstract ClassLoader getClassLoader();

    public abstract void startActivity(Intent intent);

    public abstract android.os.Looper getMainLooper();

    public final String getString(int resId) {
        return getResources().getString(resId);
    }

    public final CharSequence getText(int resId) {
        return getResources().getText(resId);
    }
}
