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
 * Does carrying the normalisation rounding with error-free transforms help?
 *
 * Helpers normalises by three plain divisions (a/=d; b/=d; c/=d), each rounding at
 * 0.5 ulp, and every compensation downstream then works on that perturbed cubic.
 * The Newton step repairs root VALUES (it uses the original coefficients) but not a
 * wrong BRANCH: a root that was never emitted cannot be refined.
 *
 * The branch decision needs no division at all. With
 *     ph = 3bd - a^2,  qh = 2a^3 - 9abd + 27cd^2
 * one has p = ph/(9d^2), q = qh/(54d^3) and
 *     D = q^2 + p^3 = (qh^2 + 4*ph^3) / (2916 d^6),
 * so sign(D) == sign(qh^2 + 4*ph^3), computable from the ORIGINAL coefficients with
 * twoProduct/twoSum in double-double.
 */
public class EFTSolve {

    // ---------------------------------------------------------------- error-free transforms
    /** hi = a*b, lo = exact rounding error */
    static double twoProductErr(double a, double b, double hi) { return Math.fma(a, b, -hi); }
    static double twoSumErr(double a, double b, double s) { double bv = s - a; return (a - (s - bv)) + (b - bv); }
    /** normalise a double-double pair so |lo| <= ulp(hi)/2 */
    static double[] quickTwoSum(double a, double b) { double s = a + b; return new double[]{ s, b - (s - a) }; }

    static double[] ddAdd(double[] x, double[] y) {
        double s = x[0] + y[0];
        double e = twoSumErr(x[0], y[0], s) + x[1] + y[1];
        return quickTwoSum(s, e);
    }
    static double[] ddMul(double[] x, double[] y) {
        double h = x[0] * y[0];
        double e = twoProductErr(x[0], y[0], h) + (x[0] * y[1] + x[1] * y[0]);
        return quickTwoSum(h, e);
    }
    static double[] ddMulD(double[] x, double f) {
        double h = x[0] * f;
        double e = twoProductErr(x[0], f, h) + x[1] * f;
        return quickTwoSum(h, e);
    }
    static double[] dd(double a) { return new double[]{ a, 0.0 }; }
    static double[] twoProd(double a, double b) { double h = a * b; return new double[]{ h, twoProductErr(a, b, h) }; }
    static double[] ddNeg(double[] x) { return new double[]{ -x[0], -x[1] }; }

    /** sign of the discriminant, from the original coefficients, no division */
    static int discriminantSign(double d, double a, double b, double c) {
        double[] a2 = twoProd(a, a);
        double[] bd = twoProd(b, d);
        double[] ph = ddAdd(ddMulD(bd, 3.0), ddNeg(a2));            // 3bd - a^2
        double[] a3 = ddMul(a2, dd(a));
        double[] abd = ddMul(twoProd(a, b), dd(d));
        double[] d2 = twoProd(d, d);
        double[] cd2 = ddMul(dd(c), d2);
        double[] qh = ddAdd(ddAdd(ddMulD(a3, 2.0), ddNeg(ddMulD(abd, 9.0))), ddMulD(cd2, 27.0));
        double[] Dh = ddAdd(ddMul(qh, qh), ddMulD(ddMul(ddMul(ph, ph), ph), 4.0));
        if (Dh[0] > 0.0) return 1;
        if (Dh[0] < 0.0) return -1;
        if (Dh[1] > 0.0) return 1;
        if (Dh[1] < 0.0) return -1;
        return 0;
    }

    /** compensated Horner: returns f(t) for d t^3 + a t^2 + b t + c with the
     *  rounding error of every product and sum folded back in (twoProduct + twoSum). */
    public static double compHorner(double d, double a, double b, double c, double t) {
        double s = d, e = 0.0;
        double[] ks = { a, b, c };
        for (double k : ks) {
            double pr = s * t;
            double pe = Math.fma(s, t, -pr);      // twoProduct error
            double s2 = pr + k;
            double se = twoSumErr(pr, k, s2);     // twoSum error
            e = Math.fma(e, t, pe + se);
            s = s2;
        }
        return s + e;
    }

    // ---------------------------------------------------------------- solvers
    /** mode 0 = committed code (sign of compensated D); mode 1 = branch on the EFT discriminant sign */
    public static int solve(final double dd_, double a, double b, double c,
                     final double[] pts, final int off, final double A, final double B, final int mode) {
        final double a0 = a, b0 = b, c0 = c;
        if (dd_ == 0.0d) {
            final int num = RootsUlpEval3.quadKahan(a, b, c, pts, off);
            return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
        }
        final int sgn = (mode == 1) ? discriminantSign(dd_, a0, b0, c0)
                          : (mode == 4) ? SignD.signD(dd_, a0, b0, c0, 0) : 0;

        a /= dd_; b /= dd_; c /= dd_;

        final double sq_A = a * a;
        final double sq_A_err = Math.fma(a, a, -sq_A);
        final double cb_A = sq_A * a;
        final double cb_A_err = Math.fma(sq_A, a, -cb_A) + sq_A_err * a;
        final double ab = a * b;
        final double ab_err = Math.fma(a, b, -ab);
        final double b3 = 3.0d * b;
        final double b3_err = Math.fma(3.0d, b, -b3);
        final double ps = b3 - sq_A;
        final double p = (ps + ((b3_err - sq_A_err) + twoSumErr(b3, -sq_A, ps))) / 9.0d;
        final double sub = a / 3.0d;
        final double t1 = 2.0d * cb_A, t1_err = 2.0d * cb_A_err;
        final double t2 = 9.0d * ab, t2_err = Math.fma(9.0d, ab, -t2) + 9.0d * ab_err;
        final double t3 = 27.0d * c, t3_err = Math.fma(27.0d, c, -t3);
        final double qs1 = t1 - t2, qs2 = qs1 + t3;
        final double q = (qs2 + (((t1_err - t2_err) + t3_err)
                + (twoSumErr(t1, -t2, qs1) + twoSumErr(qs1, t3, qs2)))) / 54.0d;
        final double sq_p = p * p;
        final double sq_p_err = Math.fma(p, p, -sq_p);
        final double cb_p_hi = sq_p * p;
        final double cb_p_err = Math.fma(sq_p, p, -cb_p_hi) + sq_p_err * p;
        final double cb_p = cb_p_hi + cb_p_err;
        final double sq_q = q * q;
        final double sq_q_err = Math.fma(q, q, -sq_q);
        final double Ds = sq_q + cb_p_hi;
        final double D = Ds + ((sq_q_err + cb_p_err) + twoSumErr(sq_q, cb_p_hi, Ds));

        final boolean triple, threeReal;
        if (mode == 1 || mode == 4) {
            triple = (sgn == 0) && (q == 0.0d);
            threeReal = (sgn < 0);
        } else {
            triple = (D == 0.0d) && (q == 0.0d);
            threeReal = (D < 0.0d);
        }
        final boolean degenerate = (mode == 1 || mode == 4) ? (sgn == 0) : (D == 0.0d);

        int num;
        if (degenerate) {
            if (triple) { pts[off] = (-sub); num = 1; }
            else {
                final double u = Math.cbrt(-q);
                pts[off] = Math.fma(2.0d, u, -sub);
                pts[off + 1] = (-u - sub);
                num = 2;
            }
        } else if (threeReal && p < 0.0d) {
            final double arg = -q / Math.sqrt(-cb_p);
            final double phi = (1.0d / 3.0d) * Math.acos(arg < -1.0d ? -1.0d : (arg > 1.0d ? 1.0d : arg));
            final double t = 2.0d * Math.sqrt(-p);
            pts[off] = Math.fma(t, Math.cos(phi), -sub);
            pts[off + 1] = Math.fma(-t, Math.cos(phi + (Math.PI / 3.0d)), -sub);
            pts[off + 2] = Math.fma(-t, Math.cos(phi - (Math.PI / 3.0d)), -sub);
            num = 3;
        } else {
            final double sqrt_D = Math.sqrt(D > 0.0d ? D : 0.0d);
            final double u = (q > 0.0d) ? -Math.cbrt(sqrt_D + q) : Math.cbrt(sqrt_D - q);
            final double v = (u != 0.0d) ? -p / u : 0.0d;
            pts[off] = (u + v - sub); num = 1;
        }

        final int steps = (mode == 3) ? 2 : 1;
        for (int it = 0; it < steps; it++) {
            for (int i = off, end = off + num; i < end; i++) {
                final double t = pts[i];
                final double f = (mode >= 2 || mode == 4) ? compHorner(dd_, a0, b0, c0, t)
                                             : Math.fma(Math.fma(Math.fma(dd_, t, a0), t, b0), t, c0);
                final double fp = Math.fma(Math.fma(3.0d * dd_, t, 2.0d * a0), t, b0);
                if ((f != 0.0d) && (fp != 0.0d)) {
                    final double nt = t - f / fp;
                    if (Double.isFinite(nt)) pts[i] = nt;
                }
            }
        }
        return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
    }

    // ---------------------------------------------------------------- scoring
    static final double A = 1e-6, B = 1.0 - 1e-6, MATCH = 1e-6;
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double sc(int half) { double s = Math.pow(10.0, uni(-half, half)); return rnd.nextBoolean() ? s : -s; }

    static final class Score {
        final String name; long lost, spurious; final ArrayList<Double> u = new ArrayList<>(); boolean sorted;
        Score(String n) { name = n; }
        void add(double[] got, int k, List<BigDecimal> refs) {
            boolean[] used = new boolean[Math.max(k, 1)];
            for (BigDecimal e : refs) {
                double ed = e.doubleValue();
                if (ed < A || ed >= B) continue;
                int best = -1; double bd = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ed) < bd) { bd = Math.abs(got[j] - ed); best = j; }
                if (best < 0 || bd > MATCH) { lost++; continue; }
                used[best] = true;
                u.add(e.subtract(new BigDecimal(got[best]), RootsUlpEval2.MC).abs().doubleValue() / Math.ulp(ed));
            }
            for (int j = 0; j < k; j++) {
                if (used[j]) continue;
                boolean near = false;
                for (BigDecimal e : refs) if (Math.abs(got[j] - e.doubleValue()) <= MATCH) near = true;
                if (!near) spurious++;
            }
        }
        double pct(double f) { if (u.isEmpty()) return Double.NaN; if (!sorted) { Collections.sort(u); sorted = true; } return u.get((int) Math.min(u.size() - 1, Math.floor(f * u.size()))); }
        double within(double x) { long k = 0; for (double v : u) if (v <= x) k++; return u.isEmpty() ? Double.NaN : 100.0 * k / u.size(); }
    }

    public static void main(String[] args) throws Exception {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 40000;
        int half = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        // identity check: sign(q^2+p^3) == sign(qh^2+4ph^3), against a 60-digit evaluation
        rnd = new Random(1L);
        MathContext mc = new MathContext(80);
        long checked = 0, mismatch = 0;
        for (int i = 0; i < 20000; i++) {
            double d = sc(half), a = sc(half), b = sc(half), c = sc(half);
            if (d == 0.0) continue;
            BigDecimal D = new BigDecimal(d), A1 = new BigDecimal(a), B1 = new BigDecimal(b), C1 = new BigDecimal(c);
            BigDecimal ph = B1.multiply(D).multiply(BigDecimal.valueOf(3)).subtract(A1.multiply(A1));
            BigDecimal qh = A1.pow(3).multiply(BigDecimal.valueOf(2))
                    .subtract(A1.multiply(B1).multiply(D).multiply(BigDecimal.valueOf(9)))
                    .add(C1.multiply(D).multiply(D).multiply(BigDecimal.valueOf(27)));
            BigDecimal Dh = qh.multiply(qh).add(ph.pow(3).multiply(BigDecimal.valueOf(4)));
            checked++;
            if (Integer.signum(Dh.signum()) != discriminantSign(d, a, b, c)) mismatch++;
        }
        System.out.printf("EFT discriminant sign vs 80-digit exact: %d checked, %d mismatches%n%n", checked, mismatch);

        String[] shapes = { "wild coefficients (" + (2 * half) + " decades)", "3 roots in [A,B)", "Marlin xPoints [0,4096]", "Marlin perpendiculardfddf [0,4096]" };
        for (int sh = 0; sh < shapes.length; sh++) {
            Score[] s = { new Score("committed (compensated D sign)"), new Score("double-double qh^2+4ph^3 sign"),
                          new Score("compensated-Horner Newton x1"), new Score("compensated-Horner Newton x2"),
                          new Score("sign(D) from critical points") };
            rnd = new Random(4242L);
            double[] got = new double[4];
            long polys = 0, trueRoots = 0;
            for (int n = 0; n < N; n++) {
                double d, a, b, c;
                if (sh == 0) { d = sc(half); a = sc(half); b = sc(half); c = sc(half); }
                else if (sh == 1) { double x = uni(A, B), y = uni(A, B), z = uni(A, B); d = sc(3);
                    a = -d * (x + y + z); b = d * (x * y + x * z + y * z); c = -d * x * y * z; }
                else if (sh == 2) { double x1 = uni(0, 4096), x2 = uni(0, 4096), x3 = uni(0, 4096), x4 = uni(0, 4096);
                    d = 3.0 * (x2 - x3) + x4 - x1; a = 3.0 * (x1 - 2.0 * x2 + x3); b = 3.0 * (x2 - x1);
                    double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
                    c = x1 - uni(lo, hi); }
                else { double[] X = new double[4], Y = new double[4];
                    for (int j = 0; j < 4; j++) { X[j] = uni(0, 4096); Y[j] = uni(0, 4096); }
                    double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
                    double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
                    double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
                    d = 2.0 * (dax * dax + day * day); a = 3.0 * (dax * dbx + day * dby);
                    b = 2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby; c = dbx * cx + dby * cy; }
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) continue;
                polys++;
                for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) trueRoots++; }
                for (int m = 0; m < 5; m++) { int k = solve(d, a, b, c, got, 0, A, B, m); s[m].add(got, k, refs); }
            }
            System.out.printf("#### %s : %d polys, %d true roots in [A,B)%n", shapes[sh], polys, trueRoots);
            System.out.printf("%-36s %8s %9s %8s %8s %10s %12s%n", "variant", "lost", "spurious", "<=1ulp", "<=2ulp", "median", "p99");
            for (Score x : s)
                System.out.printf("%-36s %8d %9d %7.2f%% %7.2f%% %10.2f %12.2f%n", x.name, x.lost, x.spurious, x.within(1), x.within(2), x.pct(0.5), x.pct(0.99));
            System.out.println();
        }
    }
}
