// SPDX-License-Identifier: GPL-3.0-or-later

package android.graphics;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * A 2D affine transform. "post" operations apply after the current matrix, "pre" operations
 * before it, as on Android. Perspective entries are not supported and read as 0, 0, 1.
 */
public class Matrix {
    public static final int MSCALE_X = 0;
    public static final int MSKEW_X = 1;
    public static final int MTRANS_X = 2;
    public static final int MSKEW_Y = 3;
    public static final int MSCALE_Y = 4;
    public static final int MTRANS_Y = 5;
    public static final int MPERSP_0 = 6;
    public static final int MPERSP_1 = 7;
    public static final int MPERSP_2 = 8;

    final AffineTransform transform = new AffineTransform();

    public enum ScaleToFit { FILL, START, CENTER, END }

    public Matrix() {}

    public Matrix(Matrix src) {
        if (src != null) transform.setTransform(src.transform);
    }

    public boolean isIdentity() {
        return transform.isIdentity();
    }

    public void reset() {
        transform.setToIdentity();
    }

    public void set(Matrix src) {
        if (src == null) reset();
        else transform.setTransform(src.transform);
    }

    public void setTranslate(float dx, float dy) {
        transform.setToTranslation(dx, dy);
    }

    public void setScale(float sx, float sy) {
        transform.setToScale(sx, sy);
    }

    public void setScale(float sx, float sy, float px, float py) {
        transform.setToIdentity();
        transform.translate(px, py);
        transform.scale(sx, sy);
        transform.translate(-px, -py);
    }

    public void setRotate(float degrees) {
        transform.setToRotation(Math.toRadians(degrees));
    }

    public void setRotate(float degrees, float px, float py) {
        transform.setToRotation(Math.toRadians(degrees), px, py);
    }

    public void setSkew(float kx, float ky) {
        transform.setToShear(kx, ky);
    }

    public boolean setConcat(Matrix a, Matrix b) {
        AffineTransform result = new AffineTransform(a.transform);
        result.concatenate(b.transform);
        transform.setTransform(result);
        return true;
    }

    private static AffineTransform scale(float sx, float sy, float px, float py) {
        AffineTransform t = AffineTransform.getTranslateInstance(px, py);
        t.scale(sx, sy);
        t.translate(-px, -py);
        return t;
    }

    private boolean post(AffineTransform t) {
        transform.preConcatenate(t);
        return true;
    }

    private boolean pre(AffineTransform t) {
        transform.concatenate(t);
        return true;
    }

    public boolean preTranslate(float dx, float dy) {
        return pre(AffineTransform.getTranslateInstance(dx, dy));
    }

    public boolean preScale(float sx, float sy) {
        return pre(AffineTransform.getScaleInstance(sx, sy));
    }

    public boolean preScale(float sx, float sy, float px, float py) {
        return pre(scale(sx, sy, px, py));
    }

    public boolean preRotate(float degrees) {
        return pre(AffineTransform.getRotateInstance(Math.toRadians(degrees)));
    }

    public boolean preRotate(float degrees, float px, float py) {
        return pre(AffineTransform.getRotateInstance(Math.toRadians(degrees), px, py));
    }

    public boolean preSkew(float kx, float ky) {
        return pre(AffineTransform.getShearInstance(kx, ky));
    }

    public boolean preConcat(Matrix other) {
        return pre(other.transform);
    }

    public boolean postTranslate(float dx, float dy) {
        return post(AffineTransform.getTranslateInstance(dx, dy));
    }

    public boolean postScale(float sx, float sy) {
        return post(AffineTransform.getScaleInstance(sx, sy));
    }

    public boolean postScale(float sx, float sy, float px, float py) {
        return post(scale(sx, sy, px, py));
    }

    public boolean postRotate(float degrees) {
        return post(AffineTransform.getRotateInstance(Math.toRadians(degrees)));
    }

    public boolean postRotate(float degrees, float px, float py) {
        return post(AffineTransform.getRotateInstance(Math.toRadians(degrees), px, py));
    }

    public boolean postSkew(float kx, float ky) {
        return post(AffineTransform.getShearInstance(kx, ky));
    }

    public boolean postConcat(Matrix other) {
        return post(other.transform);
    }

    public boolean invert(Matrix inverse) {
        try {
            inverse.transform.setTransform(transform.createInverse());
            return true;
        } catch (NoninvertibleTransformException e) {
            return false;
        }
    }

    public void getValues(float[] values) {
        values[MSCALE_X] = (float) transform.getScaleX();
        values[MSKEW_X] = (float) transform.getShearX();
        values[MTRANS_X] = (float) transform.getTranslateX();
        values[MSKEW_Y] = (float) transform.getShearY();
        values[MSCALE_Y] = (float) transform.getScaleY();
        values[MTRANS_Y] = (float) transform.getTranslateY();
        values[MPERSP_0] = 0f;
        values[MPERSP_1] = 0f;
        values[MPERSP_2] = 1f;
    }

    public void setValues(float[] values) {
        transform.setTransform(values[MSCALE_X], values[MSKEW_Y], values[MSKEW_X], values[MSCALE_Y], values[MTRANS_X], values[MTRANS_Y]);
    }

    public void mapPoints(float[] pts) {
        mapPoints(pts, pts);
    }

    public void mapPoints(float[] dst, float[] src) {
        transform.transform(src, 0, dst, 0, src.length / 2);
    }

    public boolean mapRect(RectF rect) {
        return mapRect(rect, rect);
    }

    public boolean mapRect(RectF dst, RectF src) {
        Rectangle2D bounds = transform.createTransformedShape(
            new Rectangle2D.Float(src.left, src.top, src.width(), src.height())).getBounds2D();
        dst.set((float) bounds.getMinX(), (float) bounds.getMinY(), (float) bounds.getMaxX(), (float) bounds.getMaxY());
        return transform.getShearX() == 0 && transform.getShearY() == 0;
    }

    public float mapRadius(float radius) {
        Point2D a = transform.deltaTransform(new Point2D.Float(radius, 0), null);
        Point2D b = transform.deltaTransform(new Point2D.Float(0, radius), null);
        return (float) Math.sqrt(a.distance(0, 0) * b.distance(0, 0));
    }

    public boolean setRectToRect(RectF src, RectF dst, ScaleToFit stf) {
        if (src.isEmpty()) {
            reset();
            return false;
        }
        float sx = dst.width() / src.width();
        float sy = dst.height() / src.height();
        float tx = dst.left;
        float ty = dst.top;
        if (stf != ScaleToFit.FILL) {
            float s = Math.min(sx, sy);
            float extraX = dst.width() - src.width() * s;
            float extraY = dst.height() - src.height() * s;
            if (stf == ScaleToFit.CENTER) {
                tx += extraX / 2;
                ty += extraY / 2;
            } else if (stf == ScaleToFit.END) {
                tx += extraX;
                ty += extraY;
            }
            sx = s;
            sy = s;
        }
        transform.setTransform(sx, 0, 0, sy, tx - src.left * sx, ty - src.top * sy);
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Matrix m && transform.equals(m.transform);
    }

    @Override
    public int hashCode() {
        return transform.hashCode();
    }

    @Override
    public String toString() {
        float[] v = new float[9];
        getValues(v);
        return "Matrix{[" + v[0] + ", " + v[1] + ", " + v[2] + "][" + v[3] + ", " + v[4] + ", " + v[5] + "][0.0, 0.0, 1.0]}";
    }
}
