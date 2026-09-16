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
 * What precision in t does findSubdivPoints actually need, for a pixel-coordinate
 * tolerance of 1e-3 px?
 *
 * A subdivision point t splits the curve at C(t). An error dt in t moves that point by
 * |C'(t)| * dt to first order, so the requirement is
 *
 *     dt <= 1e-3 / |C'(t)|
 *
 * At a turning point the error in THAT coordinate is second order (x'(t) = 0 there, so
 * dx ~ x''dt^2/2), but the other coordinate still moves at first order, so the speed
 * |C'| = hypot(x', y') is the right quantity unless the curve is momentarily stationary.
 * Measured over the four kinds of point findSubdivPoints actually produces.
 */
public class SubdivPrecision {
    static final double T_ERR = 1e-4, T_A = T_ERR, T_B = 1.0 - T_ERR;   // Helpers' own bounds
    static Random rnd = new Random(20260915L);
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 200000;
        double span = args.length > 1 ? Double.parseDouble(args[1]) : 4096.0;
        double tol = 1e-3;

        String[] kind = { "dxRoots / dyRoots (turning points)", "infPoints (inflections)",
                          "perpendiculardfddf (ROC cusps)", "all subdivision points" };
        ArrayList<Double>[] need = new ArrayList[4];
        for (int i = 0; i < 4; i++) need[i] = new ArrayList<>();

        double[] ts = new double[16];
        for (int n = 0; n < N; n++) {
            double x1 = uni(0, span), y1 = uni(0, span), x2 = uni(0, span), y2 = uni(0, span);
            double x3 = uni(0, span), y3 = uni(0, span), x4 = uni(0, span), y4 = uni(0, span);
            // Curve.set for a cubic: A t^3 + B t^2 + C t + D
            double ax = 3.0 * (x2 - x3) + x4 - x1, ay = 3.0 * (y2 - y3) + y4 - y1;
            double bx = 3.0 * (x1 - 2.0 * x2 + x3), by = 3.0 * (y1 - 2.0 * y2 + y3);
            double cx = 3.0 * (x2 - x1), cy = 3.0 * (y2 - y1);
            double dax = 3.0 * ax, day = 3.0 * ay, dbx = 2.0 * bx, dby = 2.0 * by;

            // dxRoots, dyRoots
            int k = FinalHelpers.quadraticRoots(dax, dbx, cx, ts, 0);
            k += FinalHelpers.quadraticRoots(day, dby, cy, ts, k);
            int nTurn = k;
            // infPoints
            double ia = dax * dby - dbx * day, ib = 2.0 * (cy * dax - day * cx), ic = cy * dbx - cx * dby;
            k += FinalHelpers.quadraticRoots(ia, ib, ic, ts, k);
            int nInf = k;
            // perpendiculardfddf, the cubic findSubdivPoints solves through rootsOfROCMinusW
            double pa = 2.0 * (dax * dax + day * day), pb = 3.0 * (dax * dbx + day * dby);
            double pc = 2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby, pd = dbx * cx + dby * cy;
            k += FinalHelpers.cubicRootsInAB(pa, pb, pc, pd, ts, k, T_A, T_B);

            for (int i = 0; i < k; i++) {
                double t = ts[i];
                if (!(t >= T_A && t < T_B)) continue;
                double sx = Math.fma(Math.fma(dax, t, dbx), t, cx);   // x'(t)
                double sy = Math.fma(Math.fma(day, t, dby), t, cy);   // y'(t)
                double speed = Math.hypot(sx, sy);
                if (speed <= 0.0) continue;
                double dt = tol / speed;
                int bucket = (i < nTurn) ? 0 : (i < nInf ? 1 : 2);
                need[bucket].add(dt);
                need[3].add(dt);
            }
        }
        System.out.printf("cubic Bezier control points uniform in [0,%.0f]^2, tolerance %.0e px%n", span, tol);
        System.out.printf("required dt = %.0e / |C'(t)| at each subdivision point%n%n", tol);
        System.out.printf("%-36s %9s %11s %11s %11s %11s%n", "point kind", "count", "median dt", "p1 dt", "p0.1 dt", "min dt");
        for (int i = 0; i < 4; i++) {
            List<Double> l = need[i];
            if (l.isEmpty()) { System.out.printf("%-36s %9d%n", kind[i], 0); continue; }
            Collections.sort(l);
            System.out.printf("%-36s %9d %11.2e %11.2e %11.2e %11.2e%n", kind[i], l.size(),
                    l.get(l.size() / 2), l.get(l.size() / 100), l.get(l.size() / 1000), l.get(0));
        }
        // how many points need tighter than each candidate precision
        System.out.printf("%n%-14s %12s %12s %12s%n", "precision", "points needing tighter", "share", "");
        for (double p : new double[]{ 1e-3, 1e-5, 1e-7, 1e-9, 1e-12 }) {
            long c = 0; for (double v : need[3]) if (v < p) c++;
            System.out.printf("%-14.0e %12d %11.4f%%%n", p, c, 100.0 * c / need[3].size());
        }
    }
}
