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
/** How loose must the residual check be to drop bogus candidates without rejecting genuine ones? */
public class ResidSweep {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static int recip(double d, double a, double b, double c, double[] pts, int off, double factor) {
        double[] scratch = new double[4];
        int n = EFTSolve.solve(d, a, b, c, pts, off, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 4);
        int m = EFTSolve.solve(c, b, a, d, scratch, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 4);
        for (int j = 0; j < m && n < 3; j++) {
            double s = scratch[j];
            if (s == 0.0 || !Double.isFinite(s)) continue;
            double t = 1.0 / s;
            if (!Double.isFinite(t)) continue;
            boolean dup = false;
            for (int i = off; i < off + n; i++)
                if (Math.abs(pts[i] - t) <= 8.0 * Math.ulp(Math.max(Math.abs(pts[i]), Math.abs(t)))) { dup = true; break; }
            if (!dup) pts[off + n++] = t;
        }
        for (int i = off; i < off + n; i++) {
            double t = pts[i];
            double f = EFTSolve.compHorner(d, a, b, c, t);
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            if (f != 0.0 && fp != 0.0) { double nt = t - f / fp; if (Double.isFinite(nt)) pts[i] = nt; }
        }
        int w = off;
        for (int i = off; i < off + n; i++) {
            boolean dup = false;
            for (int j = off; j < w; j++)
                if (Math.abs(pts[j] - pts[i]) <= 8.0 * Math.ulp(Math.max(Math.abs(pts[j]), Math.abs(pts[i])))) { dup = true; break; }
            if (dup) continue;
            if (factor > 0.0) {
                double t = pts[i], t2 = t * t;
                double scale = Math.max(Math.max(Math.abs(d * t2 * t), Math.abs(a * t2)), Math.max(Math.abs(b * t), Math.abs(c)));
                double f = Math.abs(EFTSolve.compHorner(d, a, b, c, t));
                if (scale > 0.0 && f > factor * Math.ulp(scale)) continue;
            }
            pts[w++] = pts[i];
        }
        return RootsUlpEval2.filterOutNotInAB(pts, off, w - off, A, B) - off;
    }
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 15000;
        double[] factors = { 0, 1024, 1<<20, 1e12, 1e18, 1e24 };
        for (int sh : new int[]{ 5, 1 }) {
            System.out.printf("#### %s%n", sh == 5 ? "wild coefficients, 20 decades" : "perpendiculardfddf, coords 1e-7..1e30");
            System.out.printf("%-26s %8s %9s%n", "residual factor", "lost", "spurious");
            for (double fac : factors) {
                RecipSolve.rnd = new Random(20240915L);
                double[] got = new double[8];
                long lost = 0, spur = 0;
                for (int i = 0; i < N; i++) {
                    double[] co = RecipSolve.shapeCubic(sh);
                    double d = co[0], a = co[1], b = co[2], c = co[3];
                    if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                    List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                    if (refs == null) continue;
                    int k = recip(d, a, b, c, got, 0, fac);
                    boolean[] used = new boolean[Math.max(k, 1)];
                    for (BigDecimal e : refs) {
                        double ed = e.doubleValue();
                        if (ed < A || ed >= B) continue;
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ed) < bd) { bd = Math.abs(got[j] - ed); best = j; }
                        if (best < 0 || bd > 1e-6) lost++; else used[best] = true;
                    }
                    for (int j = 0; j < k; j++) {
                        if (used[j]) continue;
                        boolean near = false;
                        for (BigDecimal e : refs) if (Math.abs(got[j] - e.doubleValue()) <= 1e-6) near = true;
                        if (!near) spur++;
                    }
                }
                System.out.printf("%-26s %8d %9d%n", fac == 0 ? "none (union only)" : String.format("%.0e * ulp(scale)", fac), lost, spur);
            }
            System.out.println();
        }
    }
}
