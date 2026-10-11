// SPDX-License-Identifier: GPL-3.0-or-later

package android.view;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android's View as a headless data holder: it keeps its id, state, size and parent but never
 * draws. {@link #post(Runnable)} runs on the main looper.
 */
public class View {
    public static final int NO_ID = -1;
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private final Context context;
    private int id = NO_ID;
    private boolean enabled = true;
    private int visibility = VISIBLE;
    private Object tag;
    ViewParent parent;
    private ViewGroup.LayoutParams layoutParams;
    private int left;
    private int top;
    private int right;
    private int bottom;
    private int measuredWidth;
    private int measuredHeight;
    private int paddingLeft;
    private int paddingTop;
    private int paddingRight;
    private int paddingBottom;
    private OnClickListener onClickListener;

    public interface OnClickListener {
        void onClick(View v);
    }

    public interface OnLongClickListener {
        boolean onLongClick(View v);
    }

    public interface OnFocusChangeListener {
        void onFocusChange(View v, boolean hasFocus);
    }

    public View(Context context) {
        this.context = context;
    }

    public static int generateViewId() {
        return NEXT_ID.getAndIncrement();
    }

    public final Context getContext() {
        return context;
    }

    public Resources getResources() {
        return context != null ? context.getResources() : Resources.getSystem();
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Object getTag() {
        return tag;
    }

    public void setTag(Object tag) {
        this.tag = tag;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVisibility() {
        return visibility;
    }

    public void setVisibility(int visibility) {
        this.visibility = visibility;
    }

    public final ViewParent getParent() {
        return parent;
    }

    public View getRootView() {
        View root = this;
        while (root.parent instanceof View p) root = p;
        return root;
    }

    public final <T extends View> T findViewById(int id) {
        return id == NO_ID ? null : findViewTraversal(id);
    }

    @SuppressWarnings("unchecked")
    <T extends View> T findViewTraversal(int id) {
        return id == this.id ? (T) this : null;
    }

    public ViewGroup.LayoutParams getLayoutParams() {
        return layoutParams;
    }

    public void setLayoutParams(ViewGroup.LayoutParams params) {
        this.layoutParams = params;
    }

    public void setOnClickListener(OnClickListener listener) {
        this.onClickListener = listener;
    }

    public void setOnLongClickListener(OnLongClickListener listener) {}

    public void setOnFocusChangeListener(OnFocusChangeListener listener) {}

    public boolean performClick() {
        if (onClickListener == null) return false;
        onClickListener.onClick(this);
        return true;
    }

    public boolean post(Runnable action) {
        return new Handler(Looper.getMainLooper()).post(action);
    }

    public boolean postDelayed(Runnable action, long delayMillis) {
        return new Handler(Looper.getMainLooper()).postDelayed(action, delayMillis);
    }

    public boolean removeCallbacks(Runnable action) {
        return true;
    }

    public void setPadding(int left, int top, int right, int bottom) {
        paddingLeft = left;
        paddingTop = top;
        paddingRight = right;
        paddingBottom = bottom;
    }

    public int getPaddingLeft() {
        return paddingLeft;
    }

    public int getPaddingTop() {
        return paddingTop;
    }

    public int getPaddingRight() {
        return paddingRight;
    }

    public int getPaddingBottom() {
        return paddingBottom;
    }

    public final void measure(int widthMeasureSpec, int heightMeasureSpec) {
        measuredWidth = MeasureSpec.getSize(widthMeasureSpec);
        measuredHeight = MeasureSpec.getSize(heightMeasureSpec);
    }

    public final int getMeasuredWidth() {
        return measuredWidth;
    }

    public final int getMeasuredHeight() {
        return measuredHeight;
    }

    public void layout(int l, int t, int r, int b) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    public final int getLeft() {
        return left;
    }

    public final int getTop() {
        return top;
    }

    public final int getRight() {
        return right;
    }

    public final int getBottom() {
        return bottom;
    }

    public final int getWidth() {
        return right - left;
    }

    public final int getHeight() {
        return bottom - top;
    }

    public void requestLayout() {}

    public void invalidate() {}

    public boolean requestFocus() {
        return false;
    }

    public void clearFocus() {}

    public boolean hasFocus() {
        return false;
    }

    public void setBackgroundColor(int color) {}

    public void setAlpha(float alpha) {}

    public void setMinimumHeight(int minHeight) {}

    public void setMinimumWidth(int minWidth) {}

    public static class MeasureSpec {
        private static final int MODE_SHIFT = 30;
        private static final int MODE_MASK = 0x3 << MODE_SHIFT;

        public static final int UNSPECIFIED = 0;
        public static final int EXACTLY = 1 << MODE_SHIFT;
        public static final int AT_MOST = 2 << MODE_SHIFT;

        public MeasureSpec() {}

        public static int makeMeasureSpec(int size, int mode) {
            return (size & ~MODE_MASK) | (mode & MODE_MASK);
        }

        public static int getMode(int measureSpec) {
            return measureSpec & MODE_MASK;
        }

        public static int getSize(int measureSpec) {
            return measureSpec & ~MODE_MASK;
        }

        public static String toString(int measureSpec) {
            int mode = getMode(measureSpec);
            String name = mode == EXACTLY ? "EXACTLY" : mode == AT_MOST ? "AT_MOST" : "UNSPECIFIED";
            return "MeasureSpec: " + name + " " + getSize(measureSpec);
        }
    }
}
