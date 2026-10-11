// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

/** A float point. */
public class PointF {
    public float x;
    public float y;

    public PointF() {}

    public PointF(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public PointF(Point p) {
        this(p.x, p.y);
    }

    public final void set(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public final void offset(float dx, float dy) {
        x += dx;
        y += dy;
    }

    public final float length() {
        return (float) Math.hypot(x, y);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PointF p && x == p.x && y == p.y;
    }

    @Override
    public int hashCode() {
        return 31 * Float.hashCode(x) + Float.hashCode(y);
    }

    @Override
    public String toString() {
        return "PointF(" + x + ", " + y + ")";
    }
}
