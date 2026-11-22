package com.crozen.dndice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A convex polyhedral die. Faces are found from the vertex cloud with a tiny
 * brute-force hull, numbered so opposite faces add up to sides + 1 (like real dice).
 */
final class Poly {

    static final class Face {
        int[] idx;          // vertex indices, counter-clockwise seen from outside
        float[] c = new float[3];   // centroid
        float[] n = new float[3];   // outward unit normal
        float[] up = new float[3];  // in-plane "up" for the number
        float[] right = new float[3];
        float inr;          // inradius (centroid to nearest edge)
        int value;          // value this face represents
        String label;
        Quat show = new Quat();   // orientation that shows this face upright to the viewer
    }

    final int sides;
    final float[][] v;
    final Face[] faces;
    final float textScale;

    private Poly(int sides, float[][] verts, float textScale) {
        this.sides = sides;
        this.textScale = textScale;
        // normalize to unit circumradius
        float max = 0;
        for (float[] p : verts) max = Math.max(max, len(p));
        v = new float[verts.length][];
        for (int i = 0; i < verts.length; i++) {
            v[i] = new float[]{verts[i][0] / max, verts[i][1] / max, verts[i][2] / max};
        }
        faces = hull(v);
    }

    // ---------------------------------------------------------------- solids

    static Poly d4() {
        return new Poly(4, new float[][]{{1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1}}, 0.95f).numbered(null);
    }

    static Poly d6() {
        List<float[]> p = new ArrayList<>();
        for (int i = 0; i < 8; i++) p.add(new float[]{(i & 1) * 2 - 1, (i >> 1 & 1) * 2 - 1, (i >> 2 & 1) * 2 - 1});
        return new Poly(6, p.toArray(new float[0][]), 1.25f).numbered(null);
    }

    static Poly d8() {
        return new Poly(8, new float[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}, 0.95f).numbered(null);
    }

    /** Pentagonal trapezohedron. labels: null → 1..10, otherwise the given strings for values 0..9. */
    static Poly d10(String[] labels) {
        double h = 1.15;
        double z = h * (1 - Math.cos(Math.PI / 5)) / (1 + Math.cos(Math.PI / 5));
        float[][] p = new float[12][];
        p[0] = new float[]{0, (float) h, 0};
        p[1] = new float[]{0, (float) -h, 0};
        for (int k = 0; k < 5; k++) {
            double a1 = k * 2 * Math.PI / 5, a2 = a1 + Math.PI / 5;
            p[2 + k] = new float[]{(float) Math.cos(a1), (float) z, (float) Math.sin(a1)};
            p[7 + k] = new float[]{(float) Math.cos(a2), (float) -z, (float) Math.sin(a2)};
        }
        Poly d = new Poly(10, p, labels == null ? 1.0f : 1.05f);
        return d.numbered(labels);
    }

    static Poly d12() {
        double f = (1 + Math.sqrt(5)) / 2, g = 1 / f;
        List<float[]> p = new ArrayList<>();
        for (int i = 0; i < 8; i++) p.add(new float[]{(i & 1) * 2 - 1, (i >> 1 & 1) * 2 - 1, (i >> 2 & 1) * 2 - 1});
        for (int s1 = -1; s1 <= 1; s1 += 2) {
            for (int s2 = -1; s2 <= 1; s2 += 2) {
                p.add(new float[]{0, (float) (s1 * g), (float) (s2 * f)});
                p.add(new float[]{(float) (s1 * g), (float) (s2 * f), 0});
                p.add(new float[]{(float) (s1 * f), 0, (float) (s2 * g)});
            }
        }
        return new Poly(12, p.toArray(new float[0][]), 1.1f).numbered(null);
    }

    static Poly d20() {
        double f = (1 + Math.sqrt(5)) / 2;
        List<float[]> p = new ArrayList<>();
        for (int s1 = -1; s1 <= 1; s1 += 2) {
            for (int s2 = -1; s2 <= 1; s2 += 2) {
                p.add(new float[]{0, s1, (float) (s2 * f)});
                p.add(new float[]{s1, (float) (s2 * f), 0});
                p.add(new float[]{(float) (s1 * f), 0, s2});
            }
        }
        return new Poly(20, p.toArray(new float[0][]), 0.92f).numbered(null);
    }

    Face faceFor(int value) {
        for (Face f : faces) if (f.value == value) return f;
        return faces[0];
    }

    // ---------------------------------------------------------------- numbering

    private Poly numbered(String[] labels) {
        int n = faces.length;
        int[] val = new int[n];
        Arrays.fill(val, -1);
        // Deterministic order: by height of the face centre, then by angle.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            float ka = faces[a].c[1] * 10 + (float) Math.atan2(faces[a].c[2], faces[a].c[0]);
            float kb = faces[b].c[1] * 10 + (float) Math.atan2(faces[b].c[2], faces[b].c[0]);
            return Float.compare(kb, ka);
        });
        int next = 1;
        // Scramble a little so neighbouring faces don't read 1,2,3 in a row.
        int[] picks = scramble(n);
        int pi = 0;
        for (int oi = 0; oi < n; oi++) {
            int i = order[picks[pi++ % n]];
            if (val[i] >= 0) continue;
            int opp = opposite(i);
            if (opp >= 0 && val[opp] < 0) {
                val[i] = next;
                val[opp] = n + 1 - next;
                next++;
            }
        }
        // Faces without an opposite (d4) or leftovers: fill sequentially.
        boolean[] used = new boolean[n + 2];
        for (int x : val) if (x > 0) used[x] = true;
        for (int i = 0; i < n; i++) {
            if (val[i] > 0) continue;
            for (int k = 1; k <= n; k++) if (!used[k]) { val[i] = k; used[k] = true; break; }
        }
        for (int i = 0; i < n; i++) {
            Face f = faces[i];
            if (labels != null) {
                f.value = val[i] - 1;                    // 0..9
                f.label = labels[f.value];
            } else {
                f.value = val[i];
                f.label = String.valueOf(f.value);
                if (sides >= 8 && (f.value == 6 || f.value == 9)) f.label = f.label + ".";
            }
        }
        return this;
    }

    private static int[] scramble(int n) {
        int[] r = new int[n];
        int step = 1;
        for (int s : new int[]{7, 5, 3}) if (gcd(s, n) == 1) { step = s; break; }
        for (int i = 0; i < n; i++) r[i] = (i * step) % n;
        return r;
    }

    private static int gcd(int a, int b) { return b == 0 ? a : gcd(b, a % b); }

    private int opposite(int i) {
        float[] a = faces[i].n;
        for (int j = 0; j < faces.length; j++) {
            if (j == i) continue;
            float[] b = faces[j].n;
            if (a[0] * b[0] + a[1] * b[1] + a[2] * b[2] < -0.999f) return j;
        }
        return -1;
    }

    // ---------------------------------------------------------------- hull

    private static Face[] hull(float[][] v) {
        int n = v.length;
        List<Face> out = new ArrayList<>();
        List<float[]> planes = new ArrayList<>();
        final float eps = 1e-3f;
        for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) for (int c = b + 1; c < n; c++) {
            float[] nrm = cross(sub(v[b], v[a]), sub(v[c], v[a]));
            float l = len(nrm);
            if (l < 1e-5f) continue;
            for (int k = 0; k < 3; k++) nrm[k] /= l;
            float d = dot(nrm, v[a]);
            if (d < 0) { for (int k = 0; k < 3; k++) nrm[k] = -nrm[k]; d = -d; }
            boolean ok = true;
            for (float[] p : v) if (dot(nrm, p) > d + eps) { ok = false; break; }
            if (!ok) continue;
            boolean dup = false;
            for (float[] pl : planes) {
                if (dot(pl, nrm) > 0.9999f) { dup = true; break; }
            }
            if (dup) continue;
            planes.add(nrm);
            List<Integer> on = new ArrayList<>();
            for (int i = 0; i < n; i++) if (Math.abs(dot(nrm, v[i]) - d) < eps) on.add(i);
            out.add(makeFace(v, on, nrm));
        }
        return out.toArray(new Face[0]);
    }

    private static Face makeFace(float[][] v, List<Integer> on, float[] nrm) {
        Face f = new Face();
        f.n = nrm;
        for (int i : on) for (int k = 0; k < 3; k++) f.c[k] += v[i][k] / on.size();
        // basis in the plane for angular sorting
        float[] e1 = sub(v[on.get(0)], f.c);
        normalizeInPlace(e1);
        float[] e2 = cross(nrm, e1);
        Integer[] idx = on.toArray(new Integer[0]);
        final float[] c = f.c;
        Arrays.sort(idx, (p, q) -> {
            float[] dp = sub(v[p], c), dq = sub(v[q], c);
            double ap = Math.atan2(dot(dp, e2), dot(dp, e1));
            double aq = Math.atan2(dot(dq, e2), dot(dq, e1));
            return Double.compare(ap, aq);
        });
        f.idx = new int[idx.length];
        for (int i = 0; i < idx.length; i++) f.idx[i] = idx[i];

        // inradius
        float inr = Float.MAX_VALUE;
        for (int i = 0; i < f.idx.length; i++) {
            float[] p = v[f.idx[i]], q = v[f.idx[(i + 1) % f.idx.length]];
            float[] e = sub(q, p);
            float[] w = sub(c, p);
            float t = dot(w, e) / dot(e, e);
            float[] foot = {p[0] + e[0] * t, p[1] + e[1] * t, p[2] + e[2] * t};
            inr = Math.min(inr, len(sub(c, foot)));
        }
        f.inr = inr;

        // "up" direction for the number
        float[] up;
        if (f.idx.length == 4 && isSquare(v, f)) {
            float[] p = v[f.idx[0]], q = v[f.idx[1]];
            up = sub(new float[]{(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2}, c);
        } else {
            int best = f.idx[0];
            float bd = -1;
            for (int i : f.idx) {
                float dd = len(sub(v[i], c));
                if (dd > bd + 1e-4f) { bd = dd; best = i; }
            }
            up = sub(v[best], c);
        }
        // remove normal component and normalize
        float k = dot(up, nrm);
        for (int i = 0; i < 3; i++) up[i] -= nrm[i] * k;
        normalizeInPlace(up);
        f.up = up;
        f.right = cross(up, nrm);
        f.show.fromBasis(f.right, f.up, f.n);
        return f;
    }

    private static boolean isSquare(float[][] v, Face f) {
        float a = len(sub(v[f.idx[0]], v[f.idx[1]]));
        float b = len(sub(v[f.idx[1]], v[f.idx[2]]));
        return Math.abs(a - b) < 1e-3f;
    }

    // ---------------------------------------------------------------- vec helpers

    static float[] sub(float[] a, float[] b) { return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    static float dot(float[] a, float[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static float len(float[] a) { return (float) Math.sqrt(dot(a, a)); }

    static void normalizeInPlace(float[] a) {
        float l = len(a);
        if (l > 1e-9f) { a[0] /= l; a[1] /= l; a[2] /= l; }
    }
}
