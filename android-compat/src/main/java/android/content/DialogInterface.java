// SPDX-License-Identifier: GPL-3.0-or-later

package android.content;

/** Android's dialog callbacks. Dialogs never show here, so the callbacks only fire when called directly. */
public interface DialogInterface {
    int BUTTON_POSITIVE = -1;
    int BUTTON_NEGATIVE = -2;
    int BUTTON_NEUTRAL = -3;

    void cancel();

    void dismiss();

    interface OnClickListener {
        void onClick(DialogInterface dialog, int which);
    }

    interface OnCancelListener {
        void onCancel(DialogInterface dialog);
    }

    interface OnDismissListener {
        void onDismiss(DialogInterface dialog);
    }

    interface OnShowListener {
        void onShow(DialogInterface dialog);
    }

    interface OnMultiChoiceClickListener {
        void onClick(DialogInterface dialog, int which, boolean isChecked);
    }
}
