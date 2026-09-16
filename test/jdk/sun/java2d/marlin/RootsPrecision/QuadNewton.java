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

/** Does one compensated-Horner Newton step make the quadratic solver correctly rounded too? */
public class QuadNewton {

    static double twoSumErr(double a, double b, double s) { double bv = s - a; return (a - (s - bv)) + (b - bv); }

    static double discriminant(double a, double b, double c) {
        double a4 = 4.0 * a, p = b * b, q = a4 * c;
        return (p - q) + (Math.fma(b, b, -p) - Math.fma(a4, c, -q));
    }

    /** compensated Horner for a*t^2 + b*t + c */
    static double compHorner2(double a, double b, double c, double t) {
        double s = a, e = 0.0;
        double pr = s * t, sm = pr + b;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, b, sm)); s = sm;
        pr = s * t; sm = pr + c;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, c, sm));
        return sm + e;
    }

    /** mode 0 = committed (Kahan discriminant), 1 = + plain fma Horner Newton, 2 = + compensated Horner Newton */
    public static int solve(double a, double b, double c, double[] z, int off, int mode) {
        int ret = off;
        if (a != 0.0) {
            double d = discriminant(a, b, c);
            if (d > 0.0) {
                d = Math.sqrt(d);
                if (b < 0.0) d = -d;
                double q = (b + d) / -2.0;
                z[ret++] = q / a;
                if (q != 0.0) z[ret++] = c / q;
            } else if (d == 0.0) z[ret++] = -b / (2.0 * a);
        } else if (b != 0.0) z[ret++] = -c / b;

        if (mode > 0) {
            for (int i = off; i < ret; i++) {
                double t = z[i];
                double f = (mode == 2) ? compHorner2(a, b, c, t) : Math.fma(Math.fma(a, t, b), t, c);
                double fp = Math.fma(2.0 * a, t, b);
                if ((f != 0.0) && (fp != 0.0)) {
                    double nt = t - f / fp;
                    if (Double.isFinite(nt)) z[i] = nt;
                }
            }
        }
        return ret - off;
    }

    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }

    /** Marlin Curve.infPoints: the inflection-point quadratic of a random cubic Bezier */
    static void marlinInf(String name, int n, RootsUlpEval3.Quad solver, double span, StringBuilder sb) {
        rnd = new Random(0x5EEDL);
        RootsUlpEval2.Stats st = new RootsUlpEval2.Stats(name);
        double[] r = new double[4];
        double A = RootsUlpEval3.A, B = RootsUlpEval3.B;
        for (int i = 0; i < n; i++) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
            double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
            double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
            double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
            double a = dax * dby - dbx * day, b = 2.0 * (cy * dax - day * cx), c = cy * dbx - cx * dby;
            int k = solver.solve(a, b, c, r, 0);
            k = RootsUlpEval2.filterOutNotInAB(r, 0, k, A, B);
            st.record(0, a, b, c, r, k, A, B);
        }
        st.print(sb);
    }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        StringBuilder sb = new StringBuilder();
        String[] names = { "Kahan only (committed)", "+ Newton, plain fma Horner", "+ Newton, compensated Horner" };
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            RootsUlpEval3.Quad s = (a, b, c, z, off) -> solve(a, b, c, z, off, mode);
            sb.append("\n== degree 2: ").append(names[m]).append(" ==\n");
            RootsUlpEval3.quadScenario("quad: 2 roots uniform in [A,B)", N, s,
                    () -> new double[]{ RootsUlpEval3.uni(RootsUlpEval3.A, RootsUlpEval3.B),
                                        RootsUlpEval3.uni(RootsUlpEval3.A, RootsUlpEval3.B) }, sb);
            for (int k : new int[]{ 2, 4, 6 }) {
                final double delta = Math.pow(10, -k);
                RootsUlpEval3.quadScenario("quad: close roots gap=1e-" + k, N, s,
                        () -> { double r1 = RootsUlpEval3.uni(0.1, 0.9); return new double[]{ r1, r1 + delta }; }, sb);
            }
            RootsUlpEval3.quadMarlinDx("quad: Marlin dxRoots [0,4096]", N, s, 4096, sb);
            marlinInf("quad: Marlin infPoints [0,4096]", N, s, 4096, sb);
        }
        System.out.print(sb);
    }
}
