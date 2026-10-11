// SPDX-License-Identifier: GPL-3.0-or-later

package android.content;

import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import java.io.File;

/** A Context that forwards every call to a base Context, as on Android. */
public class ContextWrapper extends Context {
    private Context base;

    public ContextWrapper(Context base) {
        this.base = base;
    }

    protected void attachBaseContext(Context base) {
        if (this.base != null) throw new IllegalStateException("Base context already set");
        this.base = base;
    }

    public Context getBaseContext() {
        return base;
    }

    private Context base() {
        if (base == null) throw new IllegalStateException("This context has no base context");
        return base;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return base().getSharedPreferences(name, mode);
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        return base().deleteSharedPreferences(name);
    }

    @Override
    public File getCacheDir() {
        return base().getCacheDir();
    }

    @Override
    public File getExternalCacheDir() {
        return base().getExternalCacheDir();
    }

    @Override
    public File getCodeCacheDir() {
        return base().getCodeCacheDir();
    }

    @Override
    public File getFilesDir() {
        return base().getFilesDir();
    }

    @Override
    public File getNoBackupFilesDir() {
        return base().getNoBackupFilesDir();
    }

    @Override
    public File getExternalFilesDir(String type) {
        return base().getExternalFilesDir(type);
    }

    @Override
    public File getDataDir() {
        return base().getDataDir();
    }

    @Override
    public File getDir(String name, int mode) {
        return base().getDir(name, mode);
    }

    @Override
    public String getPackageName() {
        return base().getPackageName();
    }

    @Override
    public Context getApplicationContext() {
        return base().getApplicationContext();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return base().getApplicationInfo();
    }

    @Override
    public Object getSystemService(String name) {
        return base().getSystemService(name);
    }

    @Override
    public Resources getResources() {
        return base().getResources();
    }

    @Override
    public ClassLoader getClassLoader() {
        return base().getClassLoader();
    }

    @Override
    public void startActivity(Intent intent) {
        base().startActivity(intent);
    }

    @Override
    public android.os.Looper getMainLooper() {
        return base().getMainLooper();
    }
}
