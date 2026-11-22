package com.crozen.dndice;

import java.util.Random;

/** Minimal unit quaternion for die orientation. Coordinates: x right, y up, z toward the viewer. */
final class Quat {
    double w = 1, x, y, z;

    Quat() {}

    Quat(double w, double x, double y, double z) {
        this.w = w; this.x = x; this.y = y; this.z = z;
    }

    Quat set(Quat o) {
        w = o.w; x = o.x; y = o.y; z = o.z;
        return this;
    }

    Quat setAxisAngle(double ax, double ay, double az, double angle) {
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-9) { w = 1; x = y = z = 0; return this; }
        double s = Math.sin(angle / 2) / len;
        w = Math.cos(angle / 2); x = ax * s; y = ay * s; z = az * s;
        return this;
    }

    /** this = a * b (b is applied first). Safe when this is a or b. */
    Quat mul(Quat a, Quat b) {
        double nw = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z;
        double nx = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y;
        double ny = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x;
        double nz = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w;
        w = nw; x = nx; y = ny; z = nz;
        return this;
    }

    Quat conj(Quat a) {
        w = a.w; x = -a.x; y = -a.y; z = -a.z;
        return this;
    }

    Quat normalize() {
        double n = Math.sqrt(w * w + x * x + y * y + z * z);
        if (n < 1e-12) { w = 1; x = y = z = 0; return this; }
        w /= n; x /= n; y /= n; z /= n;
        return this;
    }

    /** this = a^t (fraction t of the rotation a, along the shortest path). */
    Quat pow(Quat a, double t) {
        double aw = a.w, ax = a.x, ay = a.y, az = a.z;
        if (aw < 0) { aw = -aw; ax = -ax; ay = -ay; az = -az; }
        double s = Math.sqrt(ax * ax + ay * ay + az * az);
        if (s < 1e-9) { w = 1; x = y = z = 0; return this; }
        double half = Math.atan2(s, aw) * t;
        double k = Math.sin(half) / s;
        w = Math.cos(half); x = ax * k; y = ay * k; z = az * k;
        return this;
    }

    /** Rotates v (length 3) into out (may be the same array). */
    void rotate(float[] v, float[] out) {
        double vx = v[0], vy = v[1], vz = v[2];
        // t = 2 * cross(q.xyz, v)
        double tx = 2 * (y * vz - z * vy);
        double ty = 2 * (z * vx - x * vz);
        double tz = 2 * (x * vy - y * vx);
        out[0] = (float) (vx + w * tx + (y * tz - z * ty));
        out[1] = (float) (vy + w * ty + (z * tx - x * tz));
        out[2] = (float) (vz + w * tz + (x * ty - y * tx));
    }

    /**
     * Rotation that maps the orthonormal body vectors right, up, normal onto
     * screen x, y, z (i.e. matrix with those vectors as rows).
     */
    Quat fromBasis(float[] r, float[] u, float[] n) {
        double m00 = r[0], m01 = r[1], m02 = r[2];
        double m10 = u[0], m11 = u[1], m12 = u[2];
        double m20 = n[0], m21 = n[1], m22 = n[2];
        double tr = m00 + m11 + m22;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1) * 2;
            w = 0.25 * s; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1 + m00 - m11 - m22) * 2;
            w = (m21 - m12) / s; x = 0.25 * s; y = (m01 + m10) / s; z = (m02 + m20) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1 + m11 - m00 - m22) * 2;
            w = (m02 - m20) / s; x = (m01 + m10) / s; y = 0.25 * s; z = (m12 + m21) / s;
        } else {
            double s = Math.sqrt(1 + m22 - m00 - m11) * 2;
            w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = 0.25 * s;
        }
        return normalize();
    }

    Quat random(Random rng) {
        // Uniform random rotation (Shoemake).
        double u1 = rng.nextDouble(), u2 = rng.nextDouble() * 2 * Math.PI, u3 = rng.nextDouble() * 2 * Math.PI;
        double a = Math.sqrt(1 - u1), b = Math.sqrt(u1);
        w = a * Math.sin(u2); x = a * Math.cos(u2); y = b * Math.sin(u3); z = b * Math.cos(u3);
        return this;
    }

    static void randomAxis(Random rng, float[] out) {
        double zz = rng.nextDouble() * 2 - 1, t = rng.nextDouble() * 2 * Math.PI;
        double r = Math.sqrt(1 - zz * zz);
        out[0] = (float) (r * Math.cos(t)); out[1] = (float) (r * Math.sin(t)); out[2] = (float) zz;
    }
}
