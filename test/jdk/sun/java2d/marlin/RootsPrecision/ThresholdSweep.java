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
 * Tunes the cubic degeneracy threshold, scored against exact roots.
 *
 * The threshold family is derived rather than guessed. For a depressed cubic
 * y^3 + 3py + 2q the discriminant is Delta = -108 * D with D = q^2 + p^3, and
 * for a near-double root pair separated by delta with the third root a distance
 * g away, Delta ~ delta^2 * g^4, while g^2 ~ -3p. So
 *
 *     delta ~ sqrt(-108 D) / g^2   =>   |D| <= tau^2 * p^2 / 12
 *
 * declares a double root exactly when the two roots would be closer than tau in
 * t units -- scale-free in t, which is the domain Marlin cares about, unlike an
 * absolute test on D (fails for small roots) or one relative to max(q^2,|p^3|)
 * (fails when a large third root sets the scale).
 *
 * tau <= 0 selects the sign-of-D test with no tolerance (the committed code).
 * polish = number of Newton steps applied to each root on the ORIGINAL cubic.
 */
public class ThresholdSweep {

    static int solve(final double dd, double a, double b, double c,
                     final double[] pts, final int off, final double A, final double B,
                     final double tau, final int polish) {
        final double d0 = dd, a0 = a, b0 = b, c0 = c;   // keep originals for the polish
        if (dd == 0.0d) {
            final int num = RootsUlpEval3.quadKahan(a, b, c, pts, off);
            return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
        }
        a /= dd; b /= dd; c /= dd;

        final double sq_A = a * a;
        final double sq_A_err = Math.fma(a, a, -sq_A);
        final double cb_A = sq_A * a;
        final double cb_A_err = Math.fma(sq_A, a, -cb_A) + sq_A_err * a;
        final double ab = a * b;
        final double ab_err = Math.fma(a, b, -ab);

        final double b3 = 3.0d * b;
        final double b3_err = Math.fma(3.0d, b, -b3);
        final double ps = b3 - sq_A;
        final double p = (ps + ((b3_err - sq_A_err) + RootsUlpEval3.twoSumErr(b3, -sq_A, ps))) / 9.0d;

        final double sub = a / 3.0d;

        final double t1 = 2.0d * cb_A;
        final double t1_err = 2.0d * cb_A_err;
        final double t2 = 9.0d * ab;
        final double t2_err = Math.fma(9.0d, ab, -t2) + 9.0d * ab_err;
        final double t3 = 27.0d * c;
        final double t3_err = Math.fma(27.0d, c, -t3);
        final double qs1 = t1 - t2;
        final double qs2 = qs1 + t3;
        final double q = (qs2 + (((t1_err - t2_err) + t3_err)
                + (RootsUlpEval3.twoSumErr(t1, -t2, qs1) + RootsUlpEval3.twoSumErr(qs1, t3, qs2)))) / 54.0d;

        final double sq_p = p * p;
        final double sq_p_err = Math.fma(p, p, -sq_p);
        final double cb_p_hi = sq_p * p;
        final double cb_p_err = Math.fma(sq_p, p, -cb_p_hi) + sq_p_err * p;
        final double cb_p = cb_p_hi + cb_p_err;
        final double sq_q = q * q;
        final double sq_q_err = Math.fma(q, q, -sq_q);
        final double Ds = sq_q + cb_p_hi;
        final double D = Ds + ((sq_q_err + cb_p_err) + RootsUlpEval3.twoSumErr(sq_q, cb_p_hi, Ds));

        final boolean degenerate = (tau > 0.0d)
                ? (Math.abs(D) <= (tau * tau) * sq_p / 12.0d)
                : (D == 0.0d);

        int num;
        if (degenerate) {
            if (q == 0.0d) {
                pts[off] = (-sub); num = 1;
            } else {
                final double u = Math.cbrt(-q);
                pts[off] = Math.fma(2.0d, u, -sub);
                pts[off + 1] = (-u - sub);
                num = 2;
            }
        } else if (D < 0.0d) {
            final double phi = (1.0d / 3.0d) * Math.acos(-q / Math.sqrt(-cb_p));
            final double t = 2.0d * Math.sqrt(-p);
            pts[off] = Math.fma(t, Math.cos(phi), -sub);
            pts[off + 1] = Math.fma(-t, Math.cos(phi + (Math.PI / 3.0d)), -sub);
            pts[off + 2] = Math.fma(-t, Math.cos(phi - (Math.PI / 3.0d)), -sub);
            num = 3;
        } else {
            final double sqrt_D = Math.sqrt(D);
            final double u = (q > 0.0d) ? -Math.cbrt(sqrt_D + q) : Math.cbrt(sqrt_D - q);
            final double v = (u != 0.0d) ? -p / u : 0.0d;
            pts[off] = (u + v - sub); num = 1;
        }

        // Newton refinement on the original (un-normalised) cubic, with fma Horner
        for (int it = 0; it < polish; it++) {
            for (int i = off; i < off + num; i++) {
                final double t = pts[i];
                final double f  = Math.fma(Math.fma(Math.fma(d0, t, a0), t, b0), t, c0);
                final double fp = Math.fma(Math.fma(3.0d * d0, t, 2.0d * a0), t, b0);
                if (fp != 0.0d && f != 0.0d) {
                    final double nt = t - f / fp;
                    if (!Double.isNaN(nt) && !Double.isInfinite(nt)) pts[i] = nt;
                }
            }
        }
        return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
    }

    // ---------------------------------------------------------------- scoring
    static final double A = 1e-6, B = 1.0 - 1e-6, MATCH = 1e-6;

    static final class Score {
        final String name; long lost, spurious, matched; final ArrayList<Double> ulps = new ArrayList<>();
        Score(String n) { name = n; }
        void add(double[] got, int k, List<BigDecimal> refs) {
            boolean[] used = new boolean[Math.max(k, 1)];
            for (BigDecimal e : refs) {
                double ed = e.doubleValue();
                if (ed < A || ed >= B) continue;
                int best = -1; double bd = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ed) < bd) { bd = Math.abs(got[j] - ed); best = j; }
                if (best < 0 || bd > MATCH) { lost++; continue; }
                used[best] = true; matched++;
                ulps.add(e.subtract(new BigDecimal(got[best]), RootsUlpEval2.MC).abs().doubleValue() / Math.ulp(ed));
            }
            for (int j = 0; j < k; j++) {
                if (used[j]) continue;
                boolean near = false;
                for (BigDecimal e : refs) if (Math.abs(got[j] - e.doubleValue()) <= MATCH) near = true;
                if (!near) spurious++;
            }
        }
        boolean sorted;
        double pct(double f) {
            if (ulps.isEmpty()) return Double.NaN;
            if (!sorted) { Collections.sort(ulps); sorted = true; }
            return ulps.get((int) Math.min(ulps.size() - 1, Math.floor(f * ulps.size())));
        }
        double within(double u) { long k = 0; for (double v : ulps) if (v <= u) k++; return ulps.isEmpty() ? Double.NaN : 100.0 * k / ulps.size(); }
    }

    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }

    public static void main(String[] args) {
        final int N = args.length > 0 ? Integer.parseInt(args[0]) : 100000;
        final int decades = args.length > 1 ? Integer.parseInt(args[1]) : 20;   // total decades of coefficient range
        final int half = decades / 2;

        final double[] taus = { -1, 1e-14, 1e-12, 1e-10, 1e-8, 1e-6, 1e-4 };
        final int[] polishes = { 0, 1, 2 };

        String[] shapes = { "wild coefficients (" + decades + " decades)", "3 roots in [A,B)", "Marlin xPoints [0,4096]", "Marlin perpendiculardfddf [0,4096]" };
        for (int sh = 0; sh < shapes.length; sh++) {
            Score[][] sc = new Score[taus.length][polishes.length];
            for (int i = 0; i < taus.length; i++)
                for (int j = 0; j < polishes.length; j++)
                    sc[i][j] = new Score(String.format("tau=%-6s polish=%d", taus[i] < 0 ? "sign" : String.format("%.0e", taus[i]), polishes[j]));
            long polys = 0, uncertain = 0, trueRoots = 0;
            rnd = new Random(4242L);
            double[] got = new double[4];
            for (int n = 0; n < N; n++) {
                double d, a, b, c;
                if (sh == 0) {
                    d = sc(half); a = sc(half); b = sc(half); c = sc(half);
                } else if (sh == 1) {
                    double x = uni(A, B), y = uni(A, B), z = uni(A, B); d = sc(3);
                    a = -d * (x + y + z); b = d * (x * y + x * z + y * z); c = -d * x * y * z;
                } else if (sh == 2) {
                    double x1 = uni(0, 4096), x2 = uni(0, 4096), x3 = uni(0, 4096), x4 = uni(0, 4096);
                    d = 3.0 * (x2 - x3) + x4 - x1; a = 3.0 * (x1 - 2.0 * x2 + x3); b = 3.0 * (x2 - x1);
                    double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
                    c = x1 - uni(lo, hi);
                } else {
                    double[] X = new double[4], Y = new double[4];
                    for (int j = 0; j < 4; j++) { X[j] = uni(0, 4096); Y[j] = uni(0, 4096); }
                    double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
                    double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
                    double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
                    d = 2.0 * (dax * dax + day * day); a = 3.0 * (dax * dbx + day * dby);
                    b = 2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby; c = dbx * cx + dby * cy;
                }
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) { uncertain++; continue; }
                polys++;
                for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) trueRoots++; }
                for (int i = 0; i < taus.length; i++) {
                    for (int j = 0; j < polishes.length; j++) {
                        int k = solve(d, a, b, c, got, 0, A, B, taus[i], polishes[j]);
                        sc[i][j].add(got, k, refs);
                    }
                }
            }
            System.out.printf("%n#### %s : %d polys scored (%d reference-uncertain), %d true roots in [A,B)%n",
                    shapes[sh], polys, uncertain, trueRoots);
            System.out.printf("%-24s %8s %9s %8s %8s %8s %10s %12s%n", "variant", "lost", "spurious", "<=1ulp", "<=2ulp", "<=10ulp", "median", "p99");
            for (int i = 0; i < taus.length; i++) {
                for (int j = 0; j < polishes.length; j++) {
                    Score s = sc[i][j];
                    System.out.printf("%-24s %8d %9d %7.2f%% %7.2f%% %7.2f%% %10.2f %12.2f%n",
                            s.name, s.lost, s.spurious, s.within(1), s.within(2), s.within(10), s.pct(0.5), s.pct(0.99));
                }
            }
        }
    }
    static double sc(int half) { double s = Math.pow(10.0, uni(-half, half)); return rnd.nextBoolean() ? s : -s; }
}
