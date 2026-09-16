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
 * Hypothesis: normalising a,b,c by d with an error-free transform -- keeping the exact
 * division residual -- fixes the cubic solver.
 *
 *     ah = fl(a/d),  r = fma(-ah, d, a) == a - ah*d exactly,  a/d == ah + r/d
 *
 * so (ah, r/d) is a double-double for a/d, and p, q, sub, p^3 and D can all be carried
 * in double-double from there, removing the 1e-16 relative error they currently inherit.
 * Two separate things could benefit: the SIGN of D (which selects the branch) and the
 * LOST ROOTS on wild coefficients. Measured separately below.
 */
public class NormEFT {

    // ---------------------------------------------------------------- double-double
    static double[] qts(double a, double b) { double s = a + b; return new double[]{ s, b - (s - a) }; }
    static double[] dd(double a) { return new double[]{ a, 0.0 }; }
    static double[] neg(double[] x) { return new double[]{ -x[0], -x[1] }; }
    static double[] add(double[] x, double[] y) {
        double s = x[0] + y[0];
        double bv = s - x[0];
        double e = ((x[0] - (s - bv)) + (y[0] - bv)) + x[1] + y[1];
        return qts(s, e);
    }
    static double[] mul(double[] x, double[] y) {
        double h = x[0] * y[0];
        double e = Math.fma(x[0], y[0], -h) + (x[0] * y[1] + x[1] * y[0]);
        return qts(h, e);
    }
    static double[] mulD(double[] x, double f) {
        double h = x[0] * f;
        double e = Math.fma(x[0], f, -h) + x[1] * f;
        return qts(h, e);
    }
    static double[] divD(double[] x, double f) {
        double h = x[0] / f;
        double e = (Math.fma(-h, f, x[0]) + x[1]) / f;
        return qts(h, e);
    }
    /** error-free normalisation: a/d as a double-double */
    static double[] ddDiv(double a, double d) {
        double h = a / d;
        double e = Math.fma(-h, d, a) / d;      // residual a - h*d is exact in the fma
        return qts(h, e);
    }
    static double[] ddSqrt(double[] x) {
        if (x[0] <= 0.0) return dd(0.0);
        double s = Math.sqrt(x[0]);
        double e = (Math.fma(-s, s, x[0]) + x[1]) / (2.0 * s);
        return qts(s, e);
    }

    /** p, q, sub, p^3 and D, all double-double, from an error-free normalisation */
    static double[][] ddPQD(double d, double a, double b, double c) {
        double[] A = ddDiv(a, d), B = ddDiv(b, d), C = ddDiv(c, d);
        double[] A2 = mul(A, A);
        double[] P = divD(add(mulD(B, 3.0), neg(A2)), 9.0);                 // (3b - a^2)/9
        double[] SUB = divD(A, 3.0);
        double[] A3 = mul(A2, A);
        double[] Q = divD(add(add(mulD(A3, 2.0), neg(mulD(mul(A, B), 9.0))), mulD(C, 27.0)), 54.0);
        double[] CB = mul(mul(P, P), P);
        double[] D = add(mul(Q, Q), CB);
        return new double[][]{ P, Q, SUB, CB, D };
    }

    static int signFromDDNorm(double d, double a, double b, double c) {
        double[][] r = ddPQD(d, a, b, c);
        double[] D = r[4];
        if (D[0] > 0.0) return 1;
        if (D[0] < 0.0) return -1;
        if (D[1] > 0.0) return 1;
        if (D[1] < 0.0) return -1;
        return 0;
    }

    // ---------------------------------------------------------------- solvers
    /**
     * mode 0 = committed code (plain normalisation, critical-point sign, Newton)
     * mode 1 = double-double normalisation throughout, branch from the dd sign
     * mode 2 = double-double normalisation AND double-double root reconstruction
     */
    static int solve(double d, double a0, double b0, double c0,
                     double[] pts, int off, double A, double B, int mode) {
        if (d == 0.0) {
            int num = QuadNewton.solve(a0, b0, c0, pts, off, 2);
            return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
        }
        int num;
        if (mode == 0) {
            num = EFTSolve.solve(d, a0, b0, c0, pts, off, -1e300, 1e300, 4);
        } else {
            double[][] r = ddPQD(d, a0, b0, c0);
            double[] P = r[0], Q = r[1], SUB = r[2], CB = r[3], D = r[4];
            int sgn = (D[0] > 0.0) ? 1 : (D[0] < 0.0) ? -1 : (D[1] > 0.0) ? 1 : (D[1] < 0.0) ? -1 : 0;
            double p = P[0], q = Q[0], sub = SUB[0], cb_p = CB[0];
            if (sgn == 0) {
                if (q == 0.0) { pts[off] = -sub; num = 1; }
                else {
                    double u = Math.cbrt(-q);
                    pts[off] = Math.fma(2.0, u, -sub);
                    pts[off + 1] = -u - sub;
                    num = 2;
                }
            } else if (sgn < 0 && p < 0.0) {
                double arg = -q / Math.sqrt(-cb_p);
                double phi = (1.0 / 3.0) * Math.acos(arg < -1.0 ? -1.0 : (arg > 1.0 ? 1.0 : arg));
                if (mode == 1) {
                    double t = 2.0 * Math.sqrt(-p);
                    pts[off] = Math.fma(t, Math.cos(phi), -sub);
                    pts[off + 1] = Math.fma(-t, Math.cos(phi + Math.PI / 3.0), -sub);
                    pts[off + 2] = Math.fma(-t, Math.cos(phi - Math.PI / 3.0), -sub);
                } else {
                    // reconstruct in double-double: t*cos(phi) - sub with t and sub as dd
                    double[] T = mulD(ddSqrt(neg(P)), 2.0);
                    pts[off]     = add(mulD(T,  Math.cos(phi)), neg(SUB))[0];
                    pts[off + 1] = add(mulD(T, -Math.cos(phi + Math.PI / 3.0)), neg(SUB))[0];
                    pts[off + 2] = add(mulD(T, -Math.cos(phi - Math.PI / 3.0)), neg(SUB))[0];
                }
                num = 3;
            } else {
                double Dv = D[0] > 0.0 ? D[0] : 0.0;
                double sqrt_D = Math.sqrt(Dv);
                double u = (q > 0.0) ? -Math.cbrt(sqrt_D + q) : Math.cbrt(sqrt_D - q);
                double v = (u != 0.0) ? -p / u : 0.0;
                if (mode == 1) {
                    pts[off] = (u + v - sub);
                } else {
                    pts[off] = add(add(dd(u), dd(v)), neg(SUB))[0];
                }
                num = 1;
            }
            // same Newton step as committed
            for (int i = off; i < off + num; i++) {
                double t = pts[i];
                double f = EFTSolve.compHorner(d, a0, b0, c0, t);
                double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a0), t, b0);
                if (f != 0.0 && fp != 0.0) {
                    double nt = t - f / fp;
                    if (Double.isFinite(nt)) pts[i] = nt;
                }
            }
        }
        return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
    }

    // ---------------------------------------------------------------- measurement
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double sc(int h) { double s = Math.pow(10.0, uni(-h, h)); return rnd.nextBoolean() ? s : -s; }
    static final double A = 1e-6, B = 1.0 - 1e-6, TOL = 1e-6;

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 40000;

        // (1) does it fix the SIGN of D?
        System.out.printf("%-44s %9s %10s %10s%n", "sign(D) method", "decades", "checked", "wrong");
        for (int h : new int[]{ 6, 10, 15 }) {
            rnd = new Random(99L);
            long w1 = 0, w2 = 0, w3 = 0, n = 0;
            for (int i = 0; i < 20000; i++) {
                double d = sc(h), a = sc(h), b = sc(h), c = sc(h);
                if (d == 0.0) continue;
                int ex = SignD.exactSign(d, a, b, c);
                n++;
                if (SignD.signCompensatedD(d, a, b, c) != ex) w1++;
                if (signFromDDNorm(d, a, b, c) != ex) w2++;
                if (SignD.signD(d, a, b, c, 0) != ex) w3++;
            }
            System.out.printf("%-44s %9d %10d %10d%n", "form D, plain normalisation", 2 * h, n, w1);
            System.out.printf("%-44s %9d %10d %10d%n", "form D, error-free normalisation (dd)", 2 * h, n, w2);
            System.out.printf("%-44s %9d %10d %10d%n", "critical points (committed)", 2 * h, n, w3);
            System.out.println();
        }

        // (2) does it recover the LOST ROOTS?
        String[] shapes = { "wild (20 decades)", "wild (30 decades)", "Marlin xPoints [0,4096]", "Marlin perpendiculardfddf [0,4096]" };
        String[] names = { "committed", "dd normalisation", "dd normalisation + dd reconstruction" };
        for (int sh = 0; sh < shapes.length; sh++) {
            long[] lost = new long[3], spur = new long[3], w05 = new long[3], tot = new long[3];
            long polys = 0, trueRoots = 0;
            rnd = new Random(4242L);
            double[] got = new double[4];
            for (int i = 0; i < N; i++) {
                double d, a, b, c;
                if (sh == 0) { d = sc(10); a = sc(10); b = sc(10); c = sc(10); }
                else if (sh == 1) { d = sc(15); a = sc(15); b = sc(15); c = sc(15); }
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
                for (int m = 0; m < 3; m++) {
                    int k = solve(d, a, b, c, got, 0, A, B, m);
                    boolean[] used = new boolean[Math.max(k, 1)];
                    for (BigDecimal e : refs) {
                        double ed = e.doubleValue();
                        if (ed < A || ed >= B) continue;
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ed) < bd) { bd = Math.abs(got[j] - ed); best = j; }
                        if (best < 0 || bd > TOL) { lost[m]++; continue; }
                        used[best] = true; tot[m]++;
                        if (e.subtract(new BigDecimal(got[best]), RootsUlpEval2.MC).abs().doubleValue() / Math.ulp(ed) <= 0.5) w05[m]++;
                    }
                    for (int j = 0; j < k; j++) {
                        if (used[j]) continue;
                        boolean near = false;
                        for (BigDecimal e : refs) if (Math.abs(got[j] - e.doubleValue()) <= TOL) near = true;
                        if (!near) spur[m]++;
                    }
                }
            }
            System.out.printf("#### %s : %d polys, %d true roots in [A,B)%n", shapes[sh], polys, trueRoots);
            System.out.printf("%-40s %8s %9s %10s%n", "variant", "lost", "spurious", "<=0.5ulp");
            for (int m = 0; m < 3; m++)
                System.out.printf("%-40s %8d %9d %9.2f%%%n", names[m], lost[m], spur[m], tot[m] == 0 ? Double.NaN : 100.0 * w05[m] / tot[m]);
            System.out.println();
        }
    }
}
