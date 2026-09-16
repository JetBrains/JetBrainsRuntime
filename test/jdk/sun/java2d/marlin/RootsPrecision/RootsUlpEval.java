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

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/**
 * Numerical precision evaluation (in ulp) of sun.java2d.marlin.Helpers root solvers.
 * The solver methods below are copied verbatim from Helpers.java (JBR main).
 * Reference roots: BigDecimal Newton refinement (60 digits) of the polynomial with the
 * EXACT double coefficients that were passed to the solver. True real root count comes from
 * the exact discriminant sign computed in BigDecimal.
 */
public class RootsUlpEval {

    // ---------------------------------------------------------------- Helpers.java (verbatim)
    private static final double EPS = 1e-9d;
    static boolean within(final double x, final double y) { return within(x, y, EPS); }
    static boolean within(final double x, final double y, final double err) { return withinD(y - x, err); }
    static boolean withinD(final double d, final double err) { return (d <= err && d >= -err); }

    static int quadraticRoots(final double a, final double b, final double c,
                              final double[] zeroes, final int off) {
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

    static int cubicRootsInAB(final double d, double a, double b, double c,
                              final double[] pts, final int off, final double A, final double B) {
        if (d == 0.0d) {
            final int num = quadraticRoots(a, b, c, pts, off);
            return filterOutNotInAB(pts, off, num, A, B) - off;
        }
        a /= d; b /= d; c /= d;
        final double sq_A = a * a;
        final double p = (1.0d / 3.0d) * ((-1.0d / 3.0d) * sq_A + b);
        final double sub = (1.0d / 3.0d) * a;
        final double q = (1.0d / 2.0d) * ((2.0d / 27.0d) * a * sq_A - sub * b + c);
        final double cb_p = p * p * p;
        final double D = q * q + cb_p;
        int num;
        if (within(D, 0.0d)) {
            if (within(q, 0.0d)) {
                pts[off] = (-sub); num = 1;
            } else {
                final double u = Math.cbrt(-q);
                pts[off] = (2.0d * u - sub); pts[off + 1] = (-u - sub); num = 2;
            }
        } else if (D < 0.0d) {
            final double phi = (1.0d / 3.0d) * Math.acos(-q / Math.sqrt(-cb_p));
            final double t = 2.0d * Math.sqrt(-p);
            pts[off] = (t * Math.cos(phi) - sub);
            pts[off + 1] = (-t * Math.cos(phi + (Math.PI / 3.0d)) - sub);
            pts[off + 2] = (-t * Math.cos(phi - (Math.PI / 3.0d)) - sub);
            num = 3;
        } else {
            final double sqrt_D = Math.sqrt(D);
            final double u = Math.cbrt(sqrt_D - q);
            final double v = -Math.cbrt(sqrt_D + q);
            pts[off] = (u + v - sub); num = 1;
        }
        return filterOutNotInAB(pts, off, num, A, B) - off;
    }

    static int filterOutNotInAB(final double[] nums, final int off, final int len, final double a, final double b) {
        int ret = off;
        for (int i = off, end = off + len; i < end; i++) {
            if (nums[i] >= a && nums[i] < b) { nums[ret++] = nums[i]; }
        }
        return ret;
    }


    // ---------------------------------------------------------------- DIAGNOSTIC variant: relative tolerance on D
    static boolean RELATIVE_EPS = false;
    static int cubicRootsInABRel(final double d, double a, double b, double c,
                              final double[] pts, final int off, final double A, final double B) {
        if (d == 0.0d) {
            final int num = quadraticRoots(a, b, c, pts, off);
            return filterOutNotInAB(pts, off, num, A, B) - off;
        }
        a /= d; b /= d; c /= d;
        final double sq_A = a * a;
        final double p = (1.0d / 3.0d) * ((-1.0d / 3.0d) * sq_A + b);
        final double sub = (1.0d / 3.0d) * a;
        final double q = (1.0d / 2.0d) * ((2.0d / 27.0d) * a * sq_A - sub * b + c);
        final double cb_p = p * p * p;
        final double qq = q * q;
        final double D = qq + cb_p;
        final double tol = EPS * Math.max(qq, Math.abs(cb_p));   // relative instead of absolute
        int num;
        if (withinD(D, tol)) {
            if (within(q, 0.0d)) {
                pts[off] = (-sub); num = 1;
            } else {
                final double u = Math.cbrt(-q);
                pts[off] = (2.0d * u - sub); pts[off + 1] = (-u - sub); num = 2;
            }
        } else if (D < 0.0d) {
            final double phi = (1.0d / 3.0d) * Math.acos(-q / Math.sqrt(-cb_p));
            final double t = 2.0d * Math.sqrt(-p);
            pts[off] = (t * Math.cos(phi) - sub);
            pts[off + 1] = (-t * Math.cos(phi + (Math.PI / 3.0d)) - sub);
            pts[off + 2] = (-t * Math.cos(phi - (Math.PI / 3.0d)) - sub);
            num = 3;
        } else {
            final double sqrt_D = Math.sqrt(D);
            final double u = Math.cbrt(sqrt_D - q);
            final double v = -Math.cbrt(sqrt_D + q);
            pts[off] = (u + v - sub); num = 1;
        }
        return filterOutNotInAB(pts, off, num, A, B) - off;
    }
    static int cubic(final double d, double a, double b, double c, final double[] pts, final int off, final double A, final double B) {
        return RELATIVE_EPS ? cubicRootsInABRel(d, a, b, c, pts, off, A, B) : cubicRootsInAB(d, a, b, c, pts, off, A, B);
    }

    // ---------------------------------------------------------------- high precision reference
    static final MathContext MC = new MathContext(60);
    static final BigDecimal CONV = new BigDecimal("1e-50");

    /** Newton refine x0 on d x^3 + a x^2 + b x + c (exact double coeffs). Returns null if not converged. */
    static BigDecimal refine(double d, double a, double b, double c, double x0) {
        BigDecimal D = new BigDecimal(d), A = new BigDecimal(a), B = new BigDecimal(b), C = new BigDecimal(c);
        BigDecimal x = new BigDecimal(x0);
        BigDecimal three = BigDecimal.valueOf(3), two = BigDecimal.valueOf(2);
        for (int i = 0; i < 400; i++) {
            BigDecimal f = D.multiply(x, MC).add(A, MC).multiply(x, MC).add(B, MC).multiply(x, MC).add(C, MC);
            BigDecimal fp = D.multiply(three, MC).multiply(x, MC).add(A.multiply(two, MC), MC).multiply(x, MC).add(B, MC);
            if (fp.signum() == 0) return f.signum() == 0 ? x : null;
            BigDecimal step = f.divide(fp, MC);
            x = x.subtract(step, MC);
            BigDecimal tol = x.abs().max(new BigDecimal("1e-300")).multiply(CONV, MC);
            if (step.abs().compareTo(tol) <= 0) {
                return x;
            }
        }
        return null;
    }

    /** exact number of distinct real roots (cubic if d!=0, else quadratic/linear) */
    static int exactRealRootCount(double d, double a, double b, double c) {
        BigDecimal D = new BigDecimal(d), A = new BigDecimal(a), B = new BigDecimal(b), C = new BigDecimal(c);
        if (d == 0.0) {
            if (a == 0.0) return b != 0.0 ? 1 : 0;
            BigDecimal disc = B.multiply(B).subtract(BigDecimal.valueOf(4).multiply(A).multiply(C));
            return disc.signum() > 0 ? 2 : (disc.signum() == 0 ? 1 : 0);
        }
        // discriminant of d x^3 + a x^2 + b x + c :
        // 18 d a b c - 4 a^3 c + a^2 b^2 - 4 d b^3 - 27 d^2 c^2
        BigDecimal disc = BigDecimal.valueOf(18).multiply(D).multiply(A).multiply(B).multiply(C)
                .subtract(BigDecimal.valueOf(4).multiply(A.pow(3)).multiply(C))
                .add(A.pow(2).multiply(B.pow(2)))
                .subtract(BigDecimal.valueOf(4).multiply(D).multiply(B.pow(3)))
                .subtract(BigDecimal.valueOf(27).multiply(D.pow(2)).multiply(C.pow(2)));
        int s = disc.signum();
        if (s > 0) return 3;
        if (s < 0) return 1;
        // disc == 0: triple root iff a^2 == 3 d b
        BigDecimal delta0 = A.pow(2).subtract(BigDecimal.valueOf(3).multiply(D).multiply(B));
        return delta0.signum() == 0 ? 1 : 2;
    }

    // ---------------------------------------------------------------- statistics

    static final double FLOAT_ULP = 536870912.0; // 2^29 double ulps
    static final class Stats {
        final String name;
        long polys, rootsReturned, misclassified, missed, spurious, gross, nonconv;
        final ArrayList<Double> ulps = new ArrayList<>();   // only roots of correctly classified polys
        double maxUlp; String maxCase = "";
        double maxAbs; String maxAbsCase = "";
        Stats(String n) { name = n; }

        void record(double d, double a, double b, double c, double[] roots, int n) {
            polys++;
            rootsReturned += n;
            int exact = exactRealRootCount(d, a, b, c);
            TreeSet<String> distinct = new TreeSet<>();
            double[] errs = new double[n]; boolean ok = true;
            for (int i = 0; i < n; i++) {
                BigDecimal ref = refine(d, a, b, c, roots[i]);
                if (ref == null) { nonconv++; ok = false; errs[i] = Double.NaN; continue; }
                distinct.add(ref.round(new MathContext(25)).toString());
                double refD = ref.doubleValue();
                double abs = ref.subtract(new BigDecimal(roots[i]), MC).abs().doubleValue();
                errs[i] = abs / Math.ulp(refD);
                if (errs[i] > FLOAT_ULP) { gross++; ok = false; }
                if (abs > maxAbs) {
                    maxAbs = abs;
                    maxAbsCase = String.format("d=%s a=%s b=%s c=%s got=%s ref=%s", d, a, b, c, roots[i], ref.round(new MathContext(20)));
                }
            }
            if (distinct.size() != exact) {
                misclassified++; ok = false;
                if (distinct.size() < exact) missed++; else spurious++;
            }
            if (!ok) badPolys++;
            if (ok) {
                for (int i = 0; i < n; i++) {
                    ulps.add(errs[i]);
                    if (errs[i] > maxUlp) {
                        maxUlp = errs[i];
                        maxCase = String.format("d=%s a=%s b=%s c=%s got=%s ref=%s", d, a, b, c, roots[i], refine(d, a, b, c, roots[i]).round(new MathContext(20)));
                    }
                }
            }
        }

        double pct(double p) {
            if (ulps.isEmpty()) return Double.NaN;
            int i = (int) Math.min(ulps.size() - 1, Math.floor(p * ulps.size()));
            return ulps.get(i);
        }
        double frac(double le) { long k = 0; for (double u : ulps) if (u <= le) k++; return ulps.isEmpty() ? Double.NaN : (100.0 * k / ulps.size()); }

        void print(StringBuilder sb) {
            Collections.sort(ulps);
            sb.append(String.format("%-52s polys=%6d roots=%6d | bad polys: %5.2f%% (wrong count=%d [missed %d, spurious %d], gross roots=%d, ill-cond=%d) maxAbsErr(t)=%.3g%n",
                    name, polys, rootsReturned, 100.0 * (polys - goodPolys()) / polys, misclassified, missed, spurious, gross, nonconv, maxAbs));
            sb.append(String.format("%-52s   good roots=%6d: <=0.5ulp %5.1f%%  <=1 %5.1f%%  <=2 %5.1f%%  <=10 %5.1f%%  <=100 %5.1f%% | median %7.2f  p99 %12.2f  max %14.2f%n",
                    "", ulps.size(), frac(0.5), frac(1), frac(2), frac(10), frac(100), pct(0.5), pct(0.99), maxUlp));
            if (maxUlp > 10) sb.append("      worst good: ").append(maxCase).append('\n');
            if (misclassified + gross + nonconv > 0) sb.append("      worst abs : ").append(maxAbsCase).append('\n');
        }
        long goodPolys() { return polys - badPolys; }
        long badPolys;
    }

    // ---------------------------------------------------------------- scenarios
    static Random rnd = new Random(0x5EED);
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double scale() { double s = Math.pow(10.0, uni(-3, 3)); return rnd.nextBoolean() ? s : -s; }

    static void linear(Stats st, int n) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double b = scale() * uni(0.1, 10), c = uni(-1e4, 1e4);
            int k = quadraticRoots(0.0, b, c, r, 0);
            st.record(0, 0, b, c, r, k);
        }
    }

    static void quadFromRoots(Stats st, int n, double lo1, double hi1, double lo2, double hi2) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(lo1, hi1), r2 = uni(lo2, hi2), a = scale();
            double b = -a * (r1 + r2), c = a * r1 * r2;
            int k = quadraticRoots(a, b, c, r, 0);
            st.record(0, a, b, c, r, k);
        }
    }

    static void quadClose(Stats st, int n, double delta) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(0.1, 0.9), r2 = r1 + delta, a = scale();
            double b = -a * (r1 + r2), c = a * r1 * r2;
            int k = quadraticRoots(a, b, c, r, 0);
            st.record(0, a, b, c, r, k);
        }
    }

    /** Marlin-like: derivative quadratic dax t^2 + dbx t + cx of a random cubic Bezier (pixel coords) */
    static void quadMarlinDx(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double x1 = uni(0, span), x2 = uni(0, span), x3 = uni(0, span), x4 = uni(0, span);
            double ax = 3.0 * (x2 - x3) + x4 - x1, bx = 3.0 * (x1 - 2.0 * x2 + x3), cx = 3.0 * (x2 - x1);
            double dax = 3.0 * ax, dbx = 2.0 * bx;
            int k = quadraticRoots(dax, dbx, cx, r, 0);
            st.record(0, dax, dbx, cx, r, k);
        }
    }

    /** Marlin-like inflection points quadratic */
    static void quadMarlinInf(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
            double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
            double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
            double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
            double a = dax * dby - dbx * day, b = 2.0 * (cy * dax - day * cx), c = cy * dbx - cx * dby;
            int k = quadraticRoots(a, b, c, r, 0);
            st.record(0, a, b, c, r, k);
        }
    }

    static void cubic3Roots(Stats st, int n, double lo, double hi, double A, double B) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(lo, hi), r2 = uni(lo, hi), r3 = uni(lo, hi), d = scale();
            double a = -d * (r1 + r2 + r3), b = d * (r1 * r2 + r1 * r3 + r2 * r3), c = -d * r1 * r2 * r3;
            int k = cubic(d, a, b, c, r, 0, A, B);
            st.record(d, a, b, c, r, k);
        }
    }

    /** one real root r in [lo,hi), complex pair from t^2 + p t + q with p^2 < 4q */
    static void cubic1Root(Stats st, int n, double lo, double hi) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(lo, hi), d = scale();
            double re = uni(-2, 2), im = uni(0.01, 2);           // complex pair re +/- i im
            double p = -2 * re, q = re * re + im * im;
            double a = d * (p - r1), b = d * (q - r1 * p), c = -d * r1 * q;
            int k = cubic(d, a, b, c, r, 0, -1e300, 1e300);
            st.record(d, a, b, c, r, k);
        }
    }

    static void cubicClose(Stats st, int n, double delta, double lo, double hi) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(lo, hi), r2 = r1 + delta, r3 = uni(lo, hi), d = scale();
            double a = -d * (r1 + r2 + r3), b = d * (r1 * r2 + r1 * r3 + r2 * r3), c = -d * r1 * r2 * r3;
            int k = cubic(d, a, b, c, r, 0, -1e300, 1e300);
            st.record(d, a, b, c, r, k);
        }
    }

    static void cubicTriple(Stats st, int n) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(0.1, 0.9), d = scale();
            double a = -3 * d * r1, b = 3 * d * r1 * r1, c = -d * r1 * r1 * r1;
            int k = cubic(d, a, b, c, r, 0, -1e300, 1e300);
            st.record(d, a, b, c, r, k);
        }
    }

    /** Marlin xPoints: solve x(t) = x0 for a random cubic Bezier in pixel coords */
    static void cubicMarlinX(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double x1 = uni(0, span), x2 = uni(0, span), x3 = uni(0, span), x4 = uni(0, span);
            double ax = 3.0 * (x2 - x3) + x4 - x1, bx = 3.0 * (x1 - 2.0 * x2 + x3), cx = 3.0 * (x2 - x1), dx = x1;
            double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
            double x0 = uni(lo, hi);
            int k = cubic(ax, bx, cx, dx - x0, r, 0, -1e300, 1e300);
            st.record(ax, bx, cx, dx - x0, r, k);
        }
    }

    /** Marlin perpendiculardfddf cubic */
    static void cubicMarlinPerp(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
            double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
            double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
            double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
            double a = 2.0 * (dax * dax + day * day), b = 3.0 * (dax * dbx + day * dby);
            double c = 2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby, d = dbx * cx + dby * cy;
            int k = cubic(a, b, c, d, r, 0, -1e300, 1e300);
            st.record(a, b, c, d, r, k);
        }
    }

    public static void main(String[] args) {
        final int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        StringBuilder sb = new StringBuilder();
        sb.append("Marlin Helpers root solvers: forward error in double ulps vs 60-digit BigDecimal Newton reference, N=" + N + " polynomials per scenario\n");
        sb.append("Coefficients are built in double from chosen roots; the reference is the exact root of the rounded coefficients actually passed to the solver.\n");
        sb.append("'bad polys' = wrong number of real roots (exact discriminant), or a root off by > 1 float ulp (2^29 double ulps), or an ill-conditioned (multiple) root.\n");
        sb.append("ulp statistics are computed over roots of the remaining 'good' polys only. Interval filter [A,B) is disabled (it is exact anyway).\n\n");
        Stats s;

        sb.append("== degree 1 (quadraticRoots with a == 0) ==\n");
        s = new Stats("linear -c/b"); linear(s, N); s.print(sb);

        sb.append("\n== degree 2 (quadraticRoots) ==\n");
        s = new Stats("quad: 2 roots in [0,1)"); quadFromRoots(s, N, 0, 1, 0, 1); s.print(sb);
        s = new Stats("quad: roots in [-1e3,1e3]"); quadFromRoots(s, N, -1e3, 1e3, -1e3, 1e3); s.print(sb);
        s = new Stats("quad: r1 in [1e-8,1e-4], r2 in [0.5,1)"); quadFromRoots(s, N, 1e-8, 1e-4, 0.5, 1); s.print(sb);
        s = new Stats("quad: r1 in [1e-12,1e-8], r2 in [0.5,1)"); quadFromRoots(s, N, 1e-12, 1e-8, 0.5, 1); s.print(sb);
        for (int k = 1; k <= 8; k++) {
            s = new Stats("quad: close roots delta=1e-" + k); quadClose(s, N, Math.pow(10, -k)); s.print(sb);
        }
        s = new Stats("quad: Marlin dxRoots, coords [0,4096]"); quadMarlinDx(s, N, 4096); s.print(sb);
        s = new Stats("quad: Marlin infPoints, coords [0,4096]"); quadMarlinInf(s, N, 4096); s.print(sb);

        for (int pass = 0; pass < 2; pass++) {
            RELATIVE_EPS = (pass == 1);
            sb.append(RELATIVE_EPS
                ? "\n== degree 3 DIAGNOSTIC: same solver but within(D,0) uses tol = EPS*max(q^2,|p^3|) instead of absolute EPS ==\n"
                : "\n== degree 3 (cubicRootsInAB, as in Helpers.java) ==\n");
            s = new Stats("cubic: 3 roots in [0,1) (trig branch)"); cubic3Roots(s, N, 0, 1, -1e300, 1e300); s.print(sb);
            s = new Stats("cubic: 3 roots in [-10,10]"); cubic3Roots(s, N, -10, 10, -1e300, 1e300); s.print(sb);
            s = new Stats("cubic: 3 roots in [0,0.1]"); cubic3Roots(s, N, 0, 0.1, -1e300, 1e300); s.print(sb);
            s = new Stats("cubic: 3 roots in [0,0.01]"); cubic3Roots(s, N, 0, 0.01, -1e300, 1e300); s.print(sb);
            s = new Stats("cubic: 1 real root in [0,1) (Cardano branch)"); cubic1Root(s, N, 0, 1); s.print(sb);
            s = new Stats("cubic: 1 real root in [-10,10]"); cubic1Root(s, N, -10, 10); s.print(sb);
            for (int k = 1; k <= 8; k++) {
                s = new Stats("cubic: close pair delta=1e-" + k + ", roots in [0.1,0.9]"); cubicClose(s, N, Math.pow(10, -k), 0.1, 0.9); s.print(sb);
            }
            s = new Stats("cubic: exact triple root"); cubicTriple(s, N); s.print(sb);
            s = new Stats("cubic: Marlin xPoints, coords [0,4096]"); cubicMarlinX(s, N, 4096); s.print(sb);
            s = new Stats("cubic: Marlin xPoints, coords [0,64]"); cubicMarlinX(s, N, 64); s.print(sb);
            s = new Stats("cubic: Marlin perpendiculardfddf, coords [0,4096]"); cubicMarlinPerp(s, N, 4096); s.print(sb);
        }
        System.out.print(sb);
    }
}
