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
 * Closing the remaining bad cases: independent coefficients over up to 40 decades,
 * either sign, down to ~1e-15.
 *
 * Neither the closed form nor the reciprocal trick can beat the cancellation in
 * root = t*cos(phi) - sub once |sub|/|root| exceeds 1e16. But f is monotone between its
 * critical points, and a sign change of f across a monotone interval is a root -- and
 * sign is exactly what compensated Horner gets right at any scale. So:
 *
 *   1. split [A,B) at the critical points of f: at most three monotone pieces
 *   2. a piece whose endpoints have opposite f signs contains exactly one root
 *   3. find it with Newton safeguarded by the bracket, started from the closed-form
 *      candidate: 1-3 iterations when the closed form is good, guaranteed convergence
 *      by bisection when it is not
 *   4. a root of even multiplicity sits AT a critical point and changes no sign, so
 *      those are picked up by testing f at the critical points themselves
 *
 * This cannot lose a sign-changing root regardless of coefficient scale.
 */
public class BracketSolve {

    static final double A = 1e-6, B = 1.0 - 1e-6;

    /** critical points of f into cp[0..1], ascending; returns how many are real */
    static int criticalPoints(double d, double a, double b, double[] cp) {
        double da = 3.0 * d, db = 2.0 * a;
        double disc = FinalHelpers.discriminant(da, db, b);
        if (disc < 0.0) return 0;
        double sq = Math.sqrt(disc);
        if (db < 0.0) sq = -sq;
        double qd = (db + sq) / -2.0;
        double t1 = qd / da;
        double t2 = (qd != 0.0) ? (b / qd) : (-db / da);
        cp[0] = Math.min(t1, t2); cp[1] = Math.max(t1, t2);
        return (disc == 0.0) ? 1 : 2;
    }

    /** is t a root of the given doubles? scale-free residual test */
    static boolean isRoot(double d, double a, double b, double c, double t) {
        double t2 = t * t;
        double scale = Math.max(Math.max(Math.abs(d * t2 * t), Math.abs(a * t2)),
                                Math.max(Math.abs(b * t), Math.abs(c)));
        return scale == 0.0 || Math.abs(FinalHelpers.compHorner(d, a, b, c, t)) <= 1024.0 * Math.ulp(scale);
    }

    /**
     * Root of f in (lo,hi) given f(lo) sign, by Newton safeguarded with the bracket.
     * Falls back to bisection whenever Newton leaves the bracket, so it always converges.
     */
    static double solveBracket(double d, double a, double b, double c,
                               double lo, double hi, double flo, double guess) {
        double t = (guess > lo && guess < hi) ? guess : 0.5 * (lo + hi);
        boolean negLo = flo < 0.0;
        // keep the point with the smallest |f| seen: for a simple root that is the
        // correctly rounded double, and it is never worse than the bracket midpoint
        double best = t, fbest = Double.POSITIVE_INFINITY;
        for (int it = 0; it < 90; it++) {
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            if (f == 0.0) return t;

            // Newton from the current point, computed BEFORE the bracket shrinks so a
            // step back towards the root is not rejected as out of bounds
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;

            if ((f < 0.0) == negLo) lo = t; else hi = t;

            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                nt = 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) break;      // adjacent doubles: done
            }
            if (nt == t) break;
            t = nt;
        }
        return best;
    }

    /** both modes now call the shipped solver: mode 1 kept so the table shape is unchanged */
    static int solve(double d, double a, double b, double c, double[] pts, int off, int mode) {
        return FinalHelpers.cubicRootsInAB(d, a, b, c, pts, off, A, B);
    }

    // ---------------------------------------------------------------- shapes
    public static Random rnd;
    static double uni(double x, double y) { return x + (y - x) * rnd.nextDouble(); }
    static double mag(double loE, double hiE) { double v = Math.pow(10.0, uni(loE, hiE)); return rnd.nextBoolean() ? v : -v; }

    public static double[] shape(int sh) {
        switch (sh) {
            case 0: return new double[]{ mag(-20, 20), mag(-20, 20), mag(-20, 20), mag(-20, 20) };   // 40 decades
            case 1: return new double[]{ mag(-15, 15), mag(-15, 15), mag(-15, 15), mag(-15, 15) };   // 30 decades
            case 2: return new double[]{ mag(-10, 10), mag(-10, 10), mag(-10, 10), mag(-10, 10) };   // 20 decades
            case 3: return new double[]{ mag(-15, -5), mag(-15, -5), mag(-15, -5), mag(-15, -5) };   // all tiny, ~1e-15
            case 4: {   // three roots in [A,B), coefficients scaled over 40 decades
                double x = uni(A, B), y = uni(A, B), z = uni(A, B), d = mag(-20, 20);
                return new double[]{ d, -d * (x + y + z), d * (x * y + x * z + y * z), -d * x * y * z };
            }
            case 5: {   // perpendiculardfddf, coords 1e-7..1e30
                double[] X = new double[4], Y = new double[4];
                for (int j = 0; j < 4; j++) { X[j] = mag(-7, 30); Y[j] = mag(-7, 30); }
                double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
                double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
                double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
                return new double[]{ 2.0 * (dax * dax + day * day), 3.0 * (dax * dbx + day * dby),
                                     2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby, dbx * cx + dby * cy };
            }
            case 6: {   // xPoints, ordinary pixels
                double x1 = uni(0, 4096), x2 = uni(0, 4096), x3 = uni(0, 4096), x4 = uni(0, 4096);
                double d = 3.0 * (x2 - x3) + x4 - x1, a = 3.0 * (x1 - 2.0 * x2 + x3), b = 3.0 * (x2 - x1);
                double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
                return new double[]{ d, a, b, x1 - uni(lo, hi) };
            }
            default: {  // close pair gap 1e-6
                double r1 = uni(0.1, 0.9), r2 = r1 + 1e-6, s = uni(0.1, 0.9), d = mag(-3, 3);
                return new double[]{ d, -d * (r1 + r2 + s), d * (r1 * r2 + r1 * s + r2 * s), -d * r1 * r2 * s };
            }
        }
    }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        String[] names = { "coefficients over 40 decades", "over 30 decades", "over 20 decades",
                           "all coefficients ~1e-15..1e-5", "3 roots in [A,B), d over 40 decades",
                           "perpendiculardfddf 1e-7..1e30", "xPoints 0..4096", "close pair gap 1e-6" };
        System.out.printf("%-38s %-12s %8s %9s %10s %6s%n", "shape", "variant", "lost", "spurious", "<=0.5ulp", "NaN");
        for (int sh = 0; sh < names.length; sh++) {
            long[] lost = new long[2], spur = new long[2], ok = new long[2], tot = new long[2], nan = new long[2];
            long trueRoots = 0, polys = 0;
            rnd = new Random(777333L);
            double[] got = new double[8];
            for (int i = 0; i < N; i++) {
                double[] co = shape(sh);
                double d = co[0], a = co[1], b = co[2], c = co[3];
                if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) continue;
                polys++;
                for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) trueRoots++; }
                for (int m = 0; m < 2; m++) {
                    int k = solve(d, a, b, c, got, 0, m);
                    for (int j = 0; j < k; j++) if (!Double.isFinite(got[j])) nan[m]++;
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
            for (int m = 0; m < 2; m++)
                System.out.printf("%-38s %-12s %8d %9d %9.2f%% %6d%s%n", m == 0 ? names[sh] : "", m == 0 ? "SHIPPED" : "prototype",
                        lost[m], spur[m], tot[m] == 0 ? Double.NaN : 100.0 * ok[m] / tot[m], nan[m],
                        m == 1 ? String.format("   (%d roots)", trueRoots) : "");
        }
    }
}
