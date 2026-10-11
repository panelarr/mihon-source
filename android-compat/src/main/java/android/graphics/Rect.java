// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

/** An integer rectangle: left and top inclusive, right and bottom exclusive. */
public final class Rect {
    public int left;
    public int top;
    public int right;
    public int bottom;

    public Rect() {}

    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public Rect(Rect r) {
        if (r != null) set(r);
    }

    public void set(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public void set(Rect src) {
        set(src.left, src.top, src.right, src.bottom);
    }

    public void setEmpty() {
        set(0, 0, 0, 0);
    }

    public boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public int centerX() {
        return (left + right) >> 1;
    }

    public int centerY() {
        return (top + bottom) >> 1;
    }

    public float exactCenterX() {
        return (left + right) * 0.5f;
    }

    public float exactCenterY() {
        return (top + bottom) * 0.5f;
    }

    public void offset(int dx, int dy) {
        left += dx;
        right += dx;
        top += dy;
        bottom += dy;
    }

    public void offsetTo(int newLeft, int newTop) {
        right += newLeft - left;
        bottom += newTop - top;
        left = newLeft;
        top = newTop;
    }

    public void inset(int dx, int dy) {
        left += dx;
        top += dy;
        right -= dx;
        bottom -= dy;
    }

    public void inset(int left, int top, int right, int bottom) {
        this.left += left;
        this.top += top;
        this.right -= right;
        this.bottom -= bottom;
    }

    public boolean contains(int x, int y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(int left, int top, int right, int bottom) {
        return this.left < this.right && this.top < this.bottom
            && this.left <= left && this.top <= top && this.right >= right && this.bottom >= bottom;
    }

    public boolean contains(Rect r) {
        return contains(r.left, r.top, r.right, r.bottom);
    }

    public boolean intersect(int left, int top, int right, int bottom) {
        if (this.left < right && left < this.right && this.top < bottom && top < this.bottom) {
            this.left = Math.max(this.left, left);
            this.top = Math.max(this.top, top);
            this.right = Math.min(this.right, right);
            this.bottom = Math.min(this.bottom, bottom);
            return true;
        }
        return false;
    }

    public boolean intersect(Rect r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    public boolean intersects(int left, int top, int right, int bottom) {
        return this.left < right && left < this.right && this.top < bottom && top < this.bottom;
    }

    public static boolean intersects(Rect a, Rect b) {
        return a.intersects(b.left, b.top, b.right, b.bottom);
    }

    public boolean setIntersect(Rect a, Rect b) {
        if (!intersects(a, b)) return false;
        set(Math.max(a.left, b.left), Math.max(a.top, b.top), Math.min(a.right, b.right), Math.min(a.bottom, b.bottom));
        return true;
    }

    public void union(int left, int top, int right, int bottom) {
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

    public void union(Rect r) {
        union(r.left, r.top, r.right, r.bottom);
    }

    public void union(int x, int y) {
        left = Math.min(left, x);
        top = Math.min(top, y);
        right = Math.max(right, x);
        bottom = Math.max(bottom, y);
    }

    public void sort() {
        if (left > right) {
            int t = left;
            left = right;
            right = t;
        }
        if (top > bottom) {
            int t = top;
            top = bottom;
            bottom = t;
        }
    }

    public String flattenToString() {
        return left + " " + top + " " + right + " " + bottom;
    }

    public String toShortString() {
        return "[" + left + "," + top + "][" + right + "," + bottom + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rect r && left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * left + top) + right) + bottom;
    }

    @Override
    public String toString() {
        return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")";
    }
}
