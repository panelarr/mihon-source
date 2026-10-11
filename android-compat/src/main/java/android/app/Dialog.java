// SPDX-License-Identifier: GPL-3.0-or-later

package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.logging.Logger;

/**
 * Android's Dialog. Nothing can be shown on this host: {@link #show()} logs the dialog and then
 * cancels it on the main thread, as if the user had dismissed it, so callers waiting on the
 * dialog's listeners don't hang.
 */
public class Dialog implements DialogInterface {
    private static final Logger LOG = Logger.getLogger("android.app.Dialog");

    private final Context context;
    private boolean showing;
    private boolean cancelable = true;
    private CharSequence title;
    private View contentView;
    private OnCancelListener onCancelListener;
    private OnDismissListener onDismissListener;
    private OnShowListener onShowListener;

    public Dialog(Context context) {
        this.context = context;
    }

    public Dialog(Context context, int themeResId) {
        this(context);
    }

    public final Context getContext() {
        return context;
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public void setTitle(int titleId) {}

    CharSequence title() {
        return title;
    }

    public void setContentView(View view) {
        this.contentView = view;
    }

    public void setContentView(int layoutResID) {}

    public <T extends View> T findViewById(int id) {
        return contentView == null ? null : contentView.findViewById(id);
    }

    public void setCancelable(boolean flag) {
        this.cancelable = flag;
    }

    public void setCanceledOnTouchOutside(boolean cancel) {}

    public void setOnCancelListener(OnCancelListener listener) {
        this.onCancelListener = listener;
    }

    public void setOnDismissListener(OnDismissListener listener) {
        this.onDismissListener = listener;
    }

    public void setOnShowListener(OnShowListener listener) {
        this.onShowListener = listener;
    }

    public boolean isShowing() {
        return showing;
    }

    protected String describe() {
        return "dialog \"" + title + "\"";
    }

    public void show() {
        if (showing) return;
        showing = true;
        LOG.info(() -> "Not showing " + describe() + ": there is no UI on this host");
        if (onShowListener != null) onShowListener.onShow(this);
        new Handler(Looper.getMainLooper()).post(this::cancel);
    }

    public void hide() {}

    @Override
    public void cancel() {
        if (!showing) return;
        if (onCancelListener != null) onCancelListener.onCancel(this);
        dismiss();
    }

    @Override
    public void dismiss() {
        if (!showing) return;
        showing = false;
        if (onDismissListener != null) onDismissListener.onDismiss(this);
    }
}
