// SPDX-License-Identifier: GPL-3.0-or-later

package android.view;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Android's ViewGroup: keeps its children, in order, for lookups. Lays out nothing. */
public abstract class ViewGroup extends View implements ViewParent {
    private final List<View> children = new ArrayList<>();

    public ViewGroup(Context context) {
        super(context);
    }

    public void addView(View child) {
        addView(child, -1);
    }

    public void addView(View child, int index) {
        if (child.parent != null) {
            throw new IllegalStateException("The specified child already has a parent. Call removeView() on the child's parent first.");
        }
        child.parent = this;
        if (index < 0 || index > children.size()) children.add(child);
        else children.add(index, child);
    }

    public void addView(View child, LayoutParams params) {
        child.setLayoutParams(params);
        addView(child, -1);
    }

    public void addView(View child, int index, LayoutParams params) {
        child.setLayoutParams(params);
        addView(child, index);
    }

    public void addView(View child, int width, int height) {
        addView(child, new LayoutParams(width, height));
    }

    public void removeView(View view) {
        if (children.remove(view)) view.parent = null;
    }

    public void removeViewAt(int index) {
        removeView(children.get(index));
    }

    public void removeAllViews() {
        for (View child : children) child.parent = null;
        children.clear();
    }

    public int getChildCount() {
        return children.size();
    }

    public View getChildAt(int index) {
        return index < 0 || index >= children.size() ? null : children.get(index);
    }

    public int indexOfChild(View child) {
        return children.indexOf(child);
    }

    @Override
    @SuppressWarnings("unchecked")
    <T extends View> T findViewTraversal(int id) {
        if (id == getId()) return (T) this;
        for (View child : children) {
            T found = child.findViewTraversal(id);
            if (found != null) return found;
        }
        return null;
    }

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        @Deprecated
        public static final int FILL_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width;
        public int height;

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public LayoutParams(LayoutParams source) {
            this(source.width, source.height);
        }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin;
        public int topMargin;
        public int rightMargin;
        public int bottomMargin;

        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }

        public MarginLayoutParams(LayoutParams source) {
            super(source);
        }

        public void setMargins(int left, int top, int right, int bottom) {
            leftMargin = left;
            topMargin = top;
            rightMargin = right;
            bottomMargin = bottom;
        }
    }
}
