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
 * When is the closed form safe, so the bracket solver can be reserved for the rest?
 *
 * Its failure mode is the reconstruction root = t*cos(phi) - sub with sub = a/(3d): a root
 * far below |sub| is cancelled away. So the natural gate is on |sub| itself, which costs
 * one comparison (|a| <= K * 3 * |d|, no division). Measured here: the closed form's loss
 * rate bucketed by |sub|, and what fraction of real input falls in each bucket.
 */
public class Gate {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 25000;
        String[] names = { "perpendiculardfddf, device scale", "xPoints, device scale",
                           "20-decade coefficients", "perp, coords 1e-7..1e30" };
        for (int sh = 0; sh < 4; sh++) {
            BracketSolve.rnd = new Random(20260915L);
            rnd = new Random(20260915L);
            // buckets of log10|sub|: <0, 0-1, 1-2, 2-3, 3-4, 4-6, 6-9, >=9
            double[] edges = { 1, 10, 100, 1e3, 1e4, 1e6, 1e9, Double.POSITIVE_INFINITY };
            long[] polys = new long[edges.length], roots = new long[edges.length], lost = new long[edges.length];
            double[] got = new double[8];
            for (int i = 0; i < N; i++) {
                double[] co = sample(sh);
                if (co == null) continue;
                double d = co[0], a = co[1], b = co[2], c = co[3];
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) continue;
                double sub = Math.max(Math.max(Math.abs(a/d), Math.abs(b/d)), Math.abs(c/d));
                int bi = 0; while (bi < edges.length - 1 && sub >= edges[bi]) bi++;
                polys[bi]++;
                int k = EFTSolve.solve(d, a, b, c, got, 0, A, B, 4);    // fma closed form + Newton
                boolean[] used = new boolean[Math.max(k, 1)];
                for (BigDecimal e : refs) {
                    double ed = e.doubleValue();
                    if (ed < A || ed >= B) continue;
                    roots[bi]++;
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                    if (best < 0 || bd > 1e-6) lost[bi]++; else used[best] = true;
                }
            }
            System.out.printf("%n#### %s%n", names[sh]);
            System.out.printf("%-14s %10s %10s %10s %10s%n", "max|coef/d| <", "polys", "roots", "lost", "loss rate");
            String[] lbl = { "1", "10", "100", "1e3", "1e4", "1e6", "1e9", "inf" };
            long cp = 0, cr = 0, cl = 0;
            for (int i = 0; i < edges.length; i++) {
                cp += polys[i]; cr += roots[i]; cl += lost[i];
                System.out.printf("%-14s %10d %10d %10d %9.4f%%   (cumulative: %d polys, %d lost)%n",
                        lbl[i], polys[i], roots[i], lost[i],
                        roots[i] == 0 ? 0.0 : 100.0 * lost[i] / roots[i], cp, cl);
            }
        }
    }
    static double[] sample(int sh) {
        if (sh == 2) {
            double[] co = BracketSolve.shape(2);
            return (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) ? null : co;
        }
        if (sh == 3) {
            double[] co = BracketSolve.shape(5);
            return (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) ? null : co;
        }
        double span = 4096.0;
        double[] X = new double[4], Y = new double[4];
        for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
        double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
        double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
        if (sh == 1) {
            double lo = Math.min(Math.min(X[0],X[1]),Math.min(X[2],X[3])), hi = Math.max(Math.max(X[0],X[1]),Math.max(X[2],X[3]));
            double[] co = { ax, bx, cx, X[0] - uni(lo, hi) };
            return (co[0] == 0.0) ? null : co;
        }
        double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
        double[] co = { 2.0*(dax*dax+day*day), 3.0*(dax*dbx+day*dby),
                        2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, dbx*cx+dby*cy };
        return (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) ? null : co;
    }
}
