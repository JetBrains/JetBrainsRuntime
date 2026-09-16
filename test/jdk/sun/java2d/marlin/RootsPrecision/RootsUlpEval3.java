/*
 * Copyright 2026 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import java.util.*;

/**
 * Before/after for the fma changes applied to Helpers.java:
 *   degree 2 : Kahan's fma-based discriminant
 *   degree 3 : compensated p, q, p^3, D (fma + TwoSum), fma root reconstruction,
 *              Cardano second cube root replaced by v = -p/u
 * Reuses RootsUlpEval2's 60-digit reference and statistics, and the same seed and
 * scenario order, so every row is directly comparable with report-interval.txt.
 */
public class RootsUlpEval3 {

    public interface Quad  { int solve(double a, double b, double c, double[] z, int off); }
    interface Cubic { int solve(double d, double a, double b, double c, double[] p, int off, double A, double B); }

    // ---------------------------------------------------------------- current Helpers.java (baseline)
    private static final double EPS = 1e-9d;
    static boolean within(double x, double y) { return withinD(y - x, EPS); }
    static boolean withinD(double d, double err) { return (d <= err && d >= -err); }

    static int quadOrig(final double a, final double b, final double c, final double[] zeroes, final int off) {
        int ret = off;
        if (a != 0.0d) {
            double d = b * b - 4.0d * a * c;
            if (d > 0.0d) {
                d = Math.sqrt(d);
                if (b < 0.0d) { d = -d; }
                final double q = (b + d) / -2.0d;
                zeroes[ret++] = q / a;
                if (q != 0.0d) { zeroes[ret++] = c / q; }
            } else if (d == 0.0d) {
                zeroes[ret++] = -b / (2.0d * a);
            }
        } else if (b != 0.0d) {
            zeroes[ret++] = -c / b;
        }
        return ret - off;
    }

    // ---------------------------------------------------------------- NEW: Kahan fma discriminant
    public static double twoSumErr(final double x, final double y, final double s) {
        final double bv = s - x;
        return (x - (s - bv)) + (y - bv);
    }

    static double discriminant(final double a, final double b, final double c) {
        final double a4 = 4.0d * a;
        final double p = b * b;
        final double q = a4 * c;
        final double dp = Math.fma(b, b, -p);
        final double dq = Math.fma(a4, c, -q);
        return (p - q) + (dp - dq);
    }

    public static int quadKahan(final double a, final double b, final double c, final double[] zeroes, final int off) {
        int ret = off;
        if (a != 0.0d) {
            double d = discriminant(a, b, c);
            if (d > 0.0d) {
                d = Math.sqrt(d);
                if (b < 0.0d) { d = -d; }
                final double q = (b + d) / -2.0d;
                zeroes[ret++] = q / a;
                if (q != 0.0d) { zeroes[ret++] = c / q; }
            } else if (d == 0.0d) {
                zeroes[ret++] = -b / (2.0d * a);
            }
        } else if (b != 0.0d) {
            zeroes[ret++] = -c / b;
        }
        return ret - off;
    }

    // ---------------------------------------------------------------- cubic variants
    /** mode 0 = current Helpers.java, 1 = fma as patched, 2 = fma + relative degeneracy tolerance */
    static int cubicImpl(final double dd, double a, double b, double c,
                         final double[] pts, final int off, final double A, final double B, final int mode) {
        if (dd == 0.0d) {
            final int num = (mode == 0) ? quadOrig(a, b, c, pts, off) : quadKahan(a, b, c, pts, off);
            return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
        }
        a /= dd; b /= dd; c /= dd;

        final double p, q, sub, cb_p, D;
        if (mode == 0) {
            final double sq_A = a * a;
            p = (1.0d / 3.0d) * ((-1.0d / 3.0d) * sq_A + b);
            sub = (1.0d / 3.0d) * a;
            q = (1.0d / 2.0d) * ((2.0d / 27.0d) * a * sq_A - sub * b + c);
            cb_p = p * p * p;
            D = q * q + cb_p;
        } else {
            final double sq_A = a * a;
            final double sq_A_err = Math.fma(a, a, -sq_A);
            final double cb_A = sq_A * a;
            final double cb_A_err = Math.fma(sq_A, a, -cb_A) + sq_A_err * a;
            final double ab = a * b;
            final double ab_err = Math.fma(a, b, -ab);

            final double b3 = 3.0d * b;
            final double b3_err = Math.fma(3.0d, b, -b3);
            final double ps = b3 - sq_A;
            p = (ps + ((b3_err - sq_A_err) + twoSumErr(b3, -sq_A, ps))) / 9.0d;

            sub = a / 3.0d;

            final double t1 = 2.0d * cb_A;
            final double t1_err = 2.0d * cb_A_err;
            final double t2 = 9.0d * ab;
            final double t2_err = Math.fma(9.0d, ab, -t2) + 9.0d * ab_err;
            final double t3 = 27.0d * c;
            final double t3_err = Math.fma(27.0d, c, -t3);
            final double qs1 = t1 - t2;
            final double qs2 = qs1 + t3;
            q = (qs2 + (((t1_err - t2_err) + t3_err)
                        + (twoSumErr(t1, -t2, qs1) + twoSumErr(qs1, t3, qs2)))) / 54.0d;

            final double sq_p = p * p;
            final double sq_p_err = Math.fma(p, p, -sq_p);
            final double cb_p_hi = sq_p * p;
            final double cb_p_err = Math.fma(sq_p, p, -cb_p_hi) + sq_p_err * p;
            cb_p = cb_p_hi + cb_p_err;
            final double sq_q = q * q;
            final double sq_q_err = Math.fma(q, q, -sq_q);
            final double Ds = sq_q + cb_p_hi;
            D = Ds + ((sq_q_err + cb_p_err) + twoSumErr(sq_q, cb_p_hi, Ds));
        }

        final boolean degenerate;
        if (mode == 2) {
            degenerate = withinD(D, EPS * Math.max(q * q, Math.abs(cb_p)));
        } else if (mode == 3) {
            degenerate = (D == 0.0d);   // no tolerance at all
        } else {
            degenerate = within(D, 0.0d);
        }

        int num;
        if (degenerate) {
            if ((mode == 3) ? (q == 0.0d) : within(q, 0.0d)) {
                pts[off] = (-sub); num = 1;
            } else {
                final double u = Math.cbrt(-q);
                pts[off] = (mode == 0) ? (2.0d * u - sub) : Math.fma(2.0d, u, -sub);
                pts[off + 1] = (-u - sub);
                num = 2;
            }
        } else if (D < 0.0d) {
            final double phi = (1.0d / 3.0d) * Math.acos(-q / Math.sqrt(-cb_p));
            final double t = 2.0d * Math.sqrt(-p);
            if (mode == 0) {
                pts[off] = (t * Math.cos(phi) - sub);
                pts[off + 1] = (-t * Math.cos(phi + (Math.PI / 3.0d)) - sub);
                pts[off + 2] = (-t * Math.cos(phi - (Math.PI / 3.0d)) - sub);
            } else {
                pts[off] = Math.fma(t, Math.cos(phi), -sub);
                pts[off + 1] = Math.fma(-t, Math.cos(phi + (Math.PI / 3.0d)), -sub);
                pts[off + 2] = Math.fma(-t, Math.cos(phi - (Math.PI / 3.0d)), -sub);
            }
            num = 3;
        } else {
            final double sqrt_D = Math.sqrt(D);
            final double u, v;
            if (mode == 0) {
                u = Math.cbrt(sqrt_D - q);
                v = -Math.cbrt(sqrt_D + q);
            } else {
                u = (q > 0.0d) ? -Math.cbrt(sqrt_D + q) : Math.cbrt(sqrt_D - q);
                v = (u != 0.0d) ? -p / u : 0.0d;
            }
            pts[off] = (u + v - sub); num = 1;
        }
        return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
    }

    // ---------------------------------------------------------------- scenarios
    public static double A = 1e-6, B = 1.0 - 1e-6;
    static Random rnd;
    public static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double scale() { double s = Math.pow(10.0, uni(-3, 3)); return rnd.nextBoolean() ? s : -s; }

    public static void quadScenario(String name, int n, Quad solver, java.util.function.Supplier<double[]> gen, StringBuilder sb) {
        rnd = new Random(0x5EEDL);
        RootsUlpEval2.Stats st = new RootsUlpEval2.Stats(name);
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] rr = gen.get();
            double a = scale(), b = -a * (rr[0] + rr[1]), c = a * rr[0] * rr[1];
            int k = solver.solve(a, b, c, r, 0);
            k = RootsUlpEval2.filterOutNotInAB(r, 0, k, A, B);
            st.record(0, a, b, c, r, k, A, B);
        }
        st.print(sb);
    }

    public static void quadMarlinDx(String name, int n, Quad solver, double span, StringBuilder sb) {
        rnd = new Random(0x5EEDL);
        RootsUlpEval2.Stats st = new RootsUlpEval2.Stats(name);
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double x1 = uni(0, span), x2 = uni(0, span), x3 = uni(0, span), x4 = uni(0, span);
            double ax = 3.0 * (x2 - x3) + x4 - x1, bx = 3.0 * (x1 - 2.0 * x2 + x3), cx = 3.0 * (x2 - x1);
            double dax = 3.0 * ax, dbx = 2.0 * bx;
            int k = solver.solve(dax, dbx, cx, r, 0);
            k = RootsUlpEval2.filterOutNotInAB(r, 0, k, A, B);
            st.record(0, dax, dbx, cx, r, k, A, B);
        }
        st.print(sb);
    }

    static void cubScenario(String name, int n, Cubic solver, java.util.function.Supplier<double[]> gen, StringBuilder sb) {
        rnd = new Random(0x5EEDL);
        RootsUlpEval2.Stats st = new RootsUlpEval2.Stats(name);
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] rr = gen.get();
            double d = scale();
            double a = -d * (rr[0] + rr[1] + rr[2]);
            double b = d * (rr[0] * rr[1] + rr[0] * rr[2] + rr[1] * rr[2]);
            double c = -d * rr[0] * rr[1] * rr[2];
            int k = solver.solve(d, a, b, c, r, 0, A, B);
            st.record(d, a, b, c, r, k, A, B);
        }
        st.print(sb);
    }

    static void cub1(String name, int n, Cubic solver, StringBuilder sb) {
        rnd = new Random(0x5EEDL);
        RootsUlpEval2.Stats st = new RootsUlpEval2.Stats(name);
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(A, B), d = scale();
            double re = uni(-2, 2), im = uni(0.01, 2);
            double pp = -2 * re, qq = re * re + im * im;
            double a = d * (pp - r1), b = d * (qq - r1 * pp), c = -d * r1 * qq;
            int k = solver.solve(d, a, b, c, r, 0, A, B);
            st.record(d, a, b, c, r, k, A, B);
        }
        st.print(sb);
    }

    static void cubMarlinX(String name, int n, Cubic solver, double span, StringBuilder sb) {
        rnd = new Random(0x5EEDL);
        RootsUlpEval2.Stats st = new RootsUlpEval2.Stats(name);
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double x1 = uni(0, span), x2 = uni(0, span), x3 = uni(0, span), x4 = uni(0, span);
            double ax = 3.0 * (x2 - x3) + x4 - x1, bx = 3.0 * (x1 - 2.0 * x2 + x3), cx = 3.0 * (x2 - x1), dx = x1;
            double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
            double x0 = uni(lo, hi);
            int k = solver.solve(ax, bx, cx, dx - x0, r, 0, A, B);
            st.record(ax, bx, cx, dx - x0, r, k, A, B);
        }
        st.print(sb);
    }

    static void cubMarlinPerp(String name, int n, Cubic solver, double span, StringBuilder sb) {
        rnd = new Random(0x5EEDL);
        RootsUlpEval2.Stats st = new RootsUlpEval2.Stats(name);
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
            double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
            double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
            double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
            double a = 2.0 * (dax * dax + day * day), b = 3.0 * (dax * dbx + day * dby);
            double c = 2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby, d = dbx * cx + dby * cy;
            int k = solver.solve(a, b, c, d, r, 0, A, B);
            st.record(a, b, c, d, r, k, A, B);
        }
        st.print(sb);
    }

    public static void main(String[] args) {
        final int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        StringBuilder sb = new StringBuilder();
        sb.append("fma changes vs current Helpers.java, t in [1e-6, 1-1e-6), N=" + N + " per scenario, seed and order identical to report-interval.txt\n\n");

        Quad[] qs = { RootsUlpEval3::quadOrig, RootsUlpEval3::quadKahan };
        String[] qn = { "orig", "kahan" };
        for (int v = 0; v < 2; v++) {
            sb.append(v == 0 ? "== degree 2: current (b*b - 4ac) ==\n" : "\n== degree 2: Kahan fma discriminant ==\n");
            final Quad s = qs[v];
            quadScenario("quad[" + qn[v] + "]: 2 roots uniform in [A,B)", N, s, () -> new double[]{uni(A, B), uni(A, B)}, sb);
            for (int k = 1; k <= 7; k++) {
                final double delta = Math.pow(10, -k);
                quadScenario("quad[" + qn[v] + "]: close roots gap=1e-" + k, N, s,
                        () -> { double r1 = uni(0.1, 0.9); return new double[]{r1, r1 + delta}; }, sb);
            }
            quadMarlinDx("quad[" + qn[v] + "]: Marlin dxRoots [0,4096]", N, s, 4096, sb);
        }

        String[] cn = { "orig", "fma", "fma+rel", "fma+sign" };
        for (int v = 0; v < 4; v++) {
            final int mode = v;
            Cubic s = (d, a, b, c, p, off, lo, hi) -> cubicImpl(d, a, b, c, p, off, lo, hi, mode);
            sb.append(v == 0 ? "\n== degree 3: current Helpers.java ==\n"
                    : v == 1 ? "\n== degree 3: fma-compensated p,q,p^3,D + fma reconstruction + v=-p/u ==\n"
                    : v == 2 ? "\n== degree 3: the same, plus RELATIVE degeneracy tolerance ==\n"
                             : "\n== degree 3: the same, but branching on the SIGN of D (no tolerance) ==\n");
            cubScenario("cubic[" + cn[v] + "]: 3 roots uniform in [A,B)", N, s,
                    () -> new double[]{uni(A, B), uni(A, B), uni(A, B)}, sb);
            cubScenario("cubic[" + cn[v] + "]: 3 roots in [1e-6,0.1]", N, s,
                    () -> new double[]{uni(A, 0.1), uni(A, 0.1), uni(A, 0.1)}, sb);
            for (int k = 1; k <= 6; k++) {
                final double delta = Math.pow(10, -k);
                cubScenario("cubic[" + cn[v] + "]: close pair gap=1e-" + k, N, s,
                        () -> { double r1 = uni(0.1, 0.9); return new double[]{r1, r1 + delta, uni(0.1, 0.9)}; }, sb);
            }
            cub1("cubic[" + cn[v] + "]: 1 real root (Cardano)", N, s, sb);
            cubMarlinX("cubic[" + cn[v] + "]: Marlin xPoints [0,4096]", N, s, 4096, sb);
            cubMarlinPerp("cubic[" + cn[v] + "]: Marlin perpendiculardfddf [0,4096]", N, s, 4096, sb);
        }
        System.out.print(sb);
    }
}
