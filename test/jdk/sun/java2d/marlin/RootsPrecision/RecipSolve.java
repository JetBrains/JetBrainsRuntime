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
import java.util.*;

/**
 * Blinn's reciprocal / homogeneous idea (How to Solve a Cubic Equation, Part 5:
 * Back to Numerics, IEEE CG&A 2007): a root is a ratio, not a number.
 *
 * The depressed-cubic route subtracts sub = a/(3d), the mean of the three roots. Any
 * root far below that mean is reconstructed by cancelling |sub|/|root| digits away and
 * is destroyed. Each direction therefore computes accurately only the roots comparable
 * to the mean:
 *
 *   original   d t^3 + a t^2 + b t + c   -> accurate for the LARGE roots
 *   reversed   c s^3 + b s^2 + a s + d   -> roots s = 1/t, accurate for its own large s,
 *                                           ie for the SMALL roots t
 *
 * So neither direction alone suffices; solve both and take the union. Every candidate is
 * then refined by the compensated-Horner Newton step on the original cubic, which gives a
 * common accuracy and lets duplicates collapse.
 */
public class RecipSolve {

    static final double A = 1e-6, B = 1.0 - 1e-6;

    /** committed solver */
    static int committed(double d, double a, double b, double c, double[] pts, int off, double lo, double hi) {
        return EFTSolve.solve(d, a, b, c, pts, off, lo, hi, 4);
    }

    /**
     * mode 1 = union of both directions, deduped
     * mode 2 = the same, plus a scale-aware residual check on each candidate
     */
    static int recip(double d, double a, double b, double c, double[] pts, int off,
                     double lo, double hi, int mode) {
        final double[] scratch = new double[4];      // does not escape: scalar-replaced by C2

        // forward direction, unfiltered
        int n = committed(d, a, b, c, pts, off, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);

        // reversed direction: roots s of c s^3 + b s^2 + a s + d, then t = 1/s
        int m = committed(c, b, a, d, scratch, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        for (int j = 0; j < m && n < 3; j++) {
            final double s = scratch[j];
            if (s == 0.0 || !Double.isFinite(s)) continue;
            final double t = 1.0 / s;
            if (!Double.isFinite(t)) continue;
            // already present? compare at a few ulps so genuinely close roots stay distinct
            boolean dup = false;
            for (int i = off; i < off + n; i++) {
                final double diff = Math.abs(pts[i] - t);
                if (diff <= 8.0 * Math.ulp(Math.max(Math.abs(pts[i]), Math.abs(t)))) { dup = true; break; }
            }
            if (!dup) pts[off + n++] = t;
        }

        // one compensated Newton step on the ORIGINAL cubic for every candidate
        for (int i = off; i < off + n; i++) {
            final double t = pts[i];
            final double f = EFTSolve.compHorner(d, a, b, c, t);
            final double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            if (f != 0.0 && fp != 0.0) {
                final double nt = t - f / fp;
                if (Double.isFinite(nt)) pts[i] = nt;
            }
        }

        // collapse duplicates that the refinement brought together
        int w = off;
        for (int i = off; i < off + n; i++) {
            boolean dup = false;
            for (int j = off; j < w; j++) {
                final double diff = Math.abs(pts[j] - pts[i]);
                if (diff <= 8.0 * Math.ulp(Math.max(Math.abs(pts[j]), Math.abs(pts[i])))) { dup = true; break; }
            }
            if (dup) continue;
            if (mode == 2) {
                // scale-aware residual check: is this actually a root of the given doubles?
                final double t = pts[i];
                final double t2 = t * t;
                final double scale = Math.max(Math.max(Math.abs(d * t2 * t), Math.abs(a * t2)),
                                              Math.max(Math.abs(b * t), Math.abs(c)));
                final double f = Math.abs(EFTSolve.compHorner(d, a, b, c, t));
                if (scale > 0.0 && f > 1024.0 * Math.ulp(scale)) continue;
            }
            pts[w++] = pts[i];
        }
        return RootsUlpEval2.filterOutNotInAB(pts, off, w - off, lo, hi) - off;
    }

    // ---------------------------------------------------------------- shapes
    public static Random rnd;
    static double uni(double x, double y) { return x + (y - x) * rnd.nextDouble(); }
    /** Marlin input domain: |coord| from 1e-7 to 1e30, either sign */
    static double coord(double loExp, double hiExp) {
        double v = Math.pow(10.0, uni(loExp, hiExp));
        return rnd.nextBoolean() ? v : -v;
    }

    public static double[] shapeCubic(int sh) {
        switch (sh) {
            case 0: {   // xPoints, coordinates over [1e-7, 1e30]
                double x1 = coord(-7, 30), x2 = coord(-7, 30), x3 = coord(-7, 30), x4 = coord(-7, 30);
                double d = 3.0 * (x2 - x3) + x4 - x1, a = 3.0 * (x1 - 2.0 * x2 + x3), b = 3.0 * (x2 - x1);
                double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
                return new double[]{ d, a, b, x1 - uni(lo, hi) };
            }
            case 1: {   // perpendiculardfddf, coordinates over [1e-7, 1e30]
                double[] X = new double[4], Y = new double[4];
                for (int j = 0; j < 4; j++) { X[j] = coord(-7, 30); Y[j] = coord(-7, 30); }
                double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
                double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
                double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
                return new double[]{ 2.0 * (dax * dax + day * day), 3.0 * (dax * dbx + day * dby),
                                     2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby, dbx * cx + dby * cy };
            }
            case 2: {   // xPoints, ordinary pixel coordinates (regression guard)
                double x1 = uni(0, 4096), x2 = uni(0, 4096), x3 = uni(0, 4096), x4 = uni(0, 4096);
                double d = 3.0 * (x2 - x3) + x4 - x1, a = 3.0 * (x1 - 2.0 * x2 + x3), b = 3.0 * (x2 - x1);
                double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
                return new double[]{ d, a, b, x1 - uni(lo, hi) };
            }
            case 3: {   // three roots in [A,B) (regression guard)
                double x = uni(A, B), y = uni(A, B), z = uni(A, B), d = coord(-3, 3);
                return new double[]{ d, -d * (x + y + z), d * (x * y + x * z + y * z), -d * x * y * z };
            }
            case 5: {   // independent wild coefficients, 20 decades
                return new double[]{ coord(-10, 10), coord(-10, 10), coord(-10, 10), coord(-10, 10) };
            }
            case 6: {   // independent wild coefficients, 30 decades
                return new double[]{ coord(-15, 15), coord(-15, 15), coord(-15, 15), coord(-15, 15) };
            }
            case 4: default: {  // close pair, gap 1e-6 (regression guard for the dedupe)
                double r1 = uni(0.1, 0.9), r2 = r1 + 1e-6, s = uni(0.1, 0.9), d = coord(-3, 3);
                return new double[]{ d, -d * (r1 + r2 + s), d * (r1 * r2 + r1 * s + r2 * s), -d * r1 * r2 * s };
            }
        }
    }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 10000;
        String[] shapes = { "xPoints, coords 1e-7..1e30", "perpendiculardfddf, coords 1e-7..1e30",
                            "xPoints, coords 0..4096", "3 roots in [A,B)", "close pair gap 1e-6",
                            "wild coefficients, 20 decades", "wild coefficients, 30 decades" };
        String[] names = { "committed (forward only)", "+ reciprocal union", "+ reciprocal union + residual check" };
        for (int sh = 0; sh < shapes.length; sh++) {
            long[] lost = new long[3], spur = new long[3], ok = new long[3], tot = new long[3];
            long polys = 0, trueRoots = 0;
            rnd = new Random(20240915L);
            double[] got = new double[8];
            for (int i = 0; i < N; i++) {
                double[] co = shapeCubic(sh);
                double d = co[0], a = co[1], b = co[2], c = co[3];
                if (d == 0.0 || !Double.isFinite(d) || !Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c)) continue;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) continue;
                polys++;
                for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) trueRoots++; }
                for (int m = 0; m < 3; m++) {
                    int k = (m == 0) ? committed(d, a, b, c, got, 0, A, B) : recip(d, a, b, c, got, 0, A, B, m);
                    boolean[] used = new boolean[Math.max(k, 1)];
                    for (BigDecimal e : refs) {
                        double ed = e.doubleValue();
                        if (ed < A || ed >= B) continue;
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ed) < bd) { bd = Math.abs(got[j] - ed); best = j; }
                        if (best < 0 || bd > 1e-6) { lost[m]++; continue; }
                        used[best] = true; tot[m]++;
                        if (e.subtract(new BigDecimal(got[best]), RootsUlpEval2.MC).abs().doubleValue() / Math.ulp(ed) <= 0.5) ok[m]++;
                    }
                    for (int j = 0; j < k; j++) {
                        if (used[j]) continue;
                        boolean near = false;
                        for (BigDecimal e : refs) if (Math.abs(got[j] - e.doubleValue()) <= 1e-6) near = true;
                        if (!near) spur[m]++;
                    }
                }
            }
            System.out.printf("#### %s : %d polys, %d true roots in [A,B)%n", shapes[sh], polys, trueRoots);
            System.out.printf("%-38s %8s %9s %10s%n", "variant", "lost", "spurious", "<=0.5ulp");
            for (int m = 0; m < 3; m++)
                System.out.printf("%-38s %8d %9d %9.2f%%%n", names[m], lost[m], spur[m],
                        tot[m] == 0 ? Double.NaN : 100.0 * ok[m] / tot[m]);
            System.out.println();
        }
    }
}
