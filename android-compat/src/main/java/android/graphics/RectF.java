// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

/** A float rectangle. */
public class RectF {
    public float left;
    public float top;
    public float right;
    public float bottom;

    public RectF() {}

    public RectF(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public RectF(RectF r) {
        if (r != null) set(r);
    }

    public RectF(Rect r) {
        if (r != null) set(r);
    }

    public void set(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public void set(RectF src) {
        set(src.left, src.top, src.right, src.bottom);
    }

    public void set(Rect src) {
        set(src.left, src.top, src.right, src.bottom);
    }

    public void setEmpty() {
        set(0, 0, 0, 0);
    }

    public final boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public final float width() {
        return right - left;
    }

    public final float height() {
        return bottom - top;
    }

    public final float centerX() {
        return (left + right) * 0.5f;
    }

    public final float centerY() {
        return (top + bottom) * 0.5f;
    }

    public void offset(float dx, float dy) {
        left += dx;
        right += dx;
        top += dy;
        bottom += dy;
    }

    public void offsetTo(float newLeft, float newTop) {
        right += newLeft - left;
        bottom += newTop - top;
        left = newLeft;
        top = newTop;
    }

    public void inset(float dx, float dy) {
        left += dx;
        top += dy;
        right -= dx;
        bottom -= dy;
    }

    public boolean contains(float x, float y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(RectF r) {
        return left < right && top < bottom && left <= r.left && top <= r.top && right >= r.right && bottom >= r.bottom;
    }

    public boolean intersect(float left, float top, float right, float bottom) {
        if (this.left < right && left < this.right && this.top < bottom && top < this.bottom) {
            this.left = Math.max(this.left, left);
            this.top = Math.max(this.top, top);
            this.right = Math.min(this.right, right);
            this.bottom = Math.min(this.bottom, bottom);
            return true;
        }
        return false;
    }

    public boolean intersect(RectF r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    public boolean intersects(float left, float top, float right, float bottom) {
        return this.left < right && left < this.right && this.top < bottom && top < this.bottom;
    }

    public static boolean intersects(RectF a, RectF b) {
        return a.intersects(b.left, b.top, b.right, b.bottom);
    }

    public void union(float left, float top, float right, float bottom) {
        if (left >= right || top >= bottom) return;
        if (isEmpty()) {
            set(left, top, right, bottom);
            return;
        }
        this.left = Math.min(this.left, left);
        this.top = Math.min(this.top, top);
        this.right = Math.max(this.right, right);
        this.bottom = Math.max(this.bottom, bottom);
    }

    public void union(RectF r) {
        union(r.left, r.top, r.right, r.bottom);
    }

    public void round(Rect dst) {
        dst.set(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
    }

    public void roundOut(Rect dst) {
        dst.set((int) Math.floor(left), (int) Math.floor(top), (int) Math.ceil(right), (int) Math.ceil(bottom));
    }

    public void sort() {
        if (left > right) {
            float t = left;
            left = right;
            right = t;
        }
        if (top > bottom) {
            float t = top;
            top = bottom;
            bottom = t;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RectF r && left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * Float.hashCode(left) + Float.hashCode(top)) + Float.hashCode(right)) + Float.hashCode(bottom);
    }

    @Override
    public String toString() {
        return "RectF(" + left + ", " + top + ", " + right + ", " + bottom + ")";
    }
}
