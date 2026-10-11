// SPDX-License-Identifier: GPL-3.0-or-later

package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.Button;

/** Android's AlertDialog. See {@link Dialog}: showing one logs it and cancels it. */
public class AlertDialog extends Dialog implements DialogInterface {
    private CharSequence message;
    private final Button positive;
    private final Button negative;
    private final Button neutral;

    protected AlertDialog(Context context) {
        super(context);
        positive = new Button(context);
        negative = new Button(context);
        neutral = new Button(context);
    }

    protected AlertDialog(Context context, int themeResId) {
        this(context);
    }

    public void setMessage(CharSequence message) {
        this.message = message;
    }

    public void setView(View view) {
        setContentView(view);
    }

    public Button getButton(int whichButton) {
        return switch (whichButton) {
            case BUTTON_POSITIVE -> positive;
            case BUTTON_NEGATIVE -> negative;
            case BUTTON_NEUTRAL -> neutral;
            default -> null;
        };
    }

    @Override
    protected String describe() {
        return "alert dialog \"" + title() + "\": " + message;
    }

    public static class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private View view;
        private boolean cancelable = true;
        private OnCancelListener onCancelListener;
        private OnDismissListener onDismissListener;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder(Context context, int themeResId) {
            this(context);
        }

        public Context getContext() {
            return context;
        }

        public Builder setTitle(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder setTitle(int titleId) {
            return this;
        }

        public Builder setMessage(CharSequence message) {
            this.message = message;
            return this;
        }

        public Builder setMessage(int messageId) {
            return this;
        }

        public Builder setView(View view) {
            this.view = view;
            return this;
        }

        public Builder setView(int layoutResId) {
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            return this;
        }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setSingleChoiceItems(CharSequence[] items, int checkedItem, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setMultiChoiceItems(
            CharSequence[] items, boolean[] checkedItems, DialogInterface.OnMultiChoiceClickListener listener) {
            return this;
        }

        public Builder setOnCancelListener(OnCancelListener listener) {
            this.onCancelListener = listener;
            return this;
        }

        public Builder setOnDismissListener(OnDismissListener listener) {
            this.onDismissListener = listener;
            return this;
        }

        public AlertDialog create() {
            AlertDialog dialog = new AlertDialog(context);
            dialog.setTitle(title);
            dialog.setMessage(message);
            if (view != null) dialog.setView(view);
            dialog.setCancelable(cancelable);
            dialog.setOnCancelListener(onCancelListener);
            dialog.setOnDismissListener(onDismissListener);
            return dialog;
        }

        public AlertDialog show() {
            AlertDialog dialog = create();
            dialog.show();
            return dialog;
        }
    }
}
