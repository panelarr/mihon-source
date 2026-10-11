// SPDX-License-Identifier: GPL-3.0-or-later

package android.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.View;
import panelarr.mihonsource.compat.AndroidHost;

/**
 * Android's Activity. Activities never start on this host; an instance only links and
 * behaves as a Context backed by the host application.
 */
public class Activity extends ContextThemeWrapper {
    public static final int RESULT_OK = -1;
    public static final int RESULT_CANCELED = 0;
    public static final int RESULT_FIRST_USER = 1;

    private Intent intent = new Intent();
    private boolean finishing;
    private boolean destroyed;
    private CharSequence title;
    private View contentView;

    public Activity() {
        attachBaseContext(AndroidHost.INSTANCE.getApplication());
    }

    protected void onCreate(Bundle savedInstanceState) {}

    protected void onStart() {}

    protected void onResume() {}

    protected void onPause() {}

    protected void onStop() {}

    protected void onRestart() {}

    protected void onDestroy() {
        destroyed = true;
    }

    protected void onNewIntent(Intent intent) {}

    protected void onSaveInstanceState(Bundle outState) {}

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {}

    public void onBackPressed() {
        finish();
    }

    public final Application getApplication() {
        return AndroidHost.INSTANCE.getApplication();
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent newIntent) {
        this.intent = newIntent;
    }

    public void finish() {
        finishing = true;
    }

    public void finishAffinity() {
        finishing = true;
    }

    public boolean isFinishing() {
        return finishing;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public boolean isChangingConfigurations() {
        return false;
    }

    public void setContentView(View view) {
        this.contentView = view;
    }

    public void setContentView(int layoutResID) {}

    public <T extends View> T findViewById(int id) {
        return contentView == null ? null : contentView.findViewById(id);
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public void setTitle(int titleId) {}

    public final CharSequence getTitle() {
        return title;
    }

    public final void setResult(int resultCode) {}

    public final void setResult(int resultCode, Intent data) {}

    public void startActivityForResult(Intent intent, int requestCode) {
        startActivity(intent);
    }

    public final void runOnUiThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }
}
