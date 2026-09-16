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
 * Smallest iteration cap that keeps every subdivision point within a given PIXEL
 * tolerance of its true position.
 *
 * Measured directly in pixel space rather than through dt: for each true root t* of the
 * perpendiculardfddf cubic (the one findSubdivPoints solves via rootsOfROCMinusW), the
 * error is |C(t) - C(t*)| in pixels, where t is what the solver returned. A root the
 * solver misses entirely is counted separately -- a missing subdivision point is not a
 * small error.
 *
 * Only the cubic depends on the cap; dxRoots, dyRoots and infPoints go through
 * quadraticRoots, which is correctly rounded and cap-free.
 */
public class PixelCap {
    static final double T_A = 1e-4, T_B = 1.0 - 1e-4;     // Helpers' bounds
    static final int[] CAPS = { 4, 6, 8, 10, 12, 14, 16, 20, 24, 32, 40 };
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        double tol = 1.0 / 512.0;
        System.out.printf("tolerance %.6f px (1/512); position error |C(t) - C(t*)| at each ROC-cusp root%n", tol);

        for (double span : new double[]{ 4096.0, 32768.0, 1.0e6 }) {
            // build the sample: curves, their cubic, and the exact roots of that cubic
            rnd = new Random(8080811L);
            ArrayList<double[]> curves = new ArrayList<>();   // X0..X3, Y0..Y3
            ArrayList<double[]> cubics = new ArrayList<>();
            ArrayList<double[]> trueRoots = new ArrayList<>();
            while (curves.size() < N) {
                double[] X = new double[4], Y = new double[4];
                for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
                double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
                double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
                double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
                double pa = 2.0*(dax*dax+day*day), pb = 3.0*(dax*dbx+day*dby);
                double pc = 2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, pd = dbx*cx+dby*cy;
                if (pa == 0.0 || !Double.isFinite(pa+pb+pc+pd)) continue;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(pa, pb, pc, pd);
                if (refs == null) continue;
                ArrayList<Double> in = new ArrayList<>();
                for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= T_A && ed < T_B) in.add(ed); }
                if (in.isEmpty()) continue;
                curves.add(new double[]{ X[0], X[1], X[2], X[3], Y[0], Y[1], Y[2], Y[3] });
                cubics.add(new double[]{ pa, pb, pc, pd });
                double[] rr = new double[in.size()];
                for (int i = 0; i < rr.length; i++) rr[i] = in.get(i);
                trueRoots.add(rr);
            }
            long total = 0; for (double[] rr : trueRoots) total += rr.length;

            System.out.printf("%n#### control points in [0,%.0f]^2 : %d curves, %d ROC-cusp roots%n", span, curves.size(), total);
            System.out.printf("%-22s %10s %12s %12s %12s%n", "solver", "missed", "median px", "p99.99 px", "max px");
            CapSweep.A = T_A; CapSweep.B = T_B;
            for (int ci = -1; ci < CAPS.length; ci++) {
                if (ci >= 0) CapSweep.CAP = CAPS[ci];
                double[] got = new double[8];
                ArrayList<Double> errs = new ArrayList<>();
                long missed = 0;
                for (int p = 0; p < curves.size(); p++) {
                    double[] cu = curves.get(p), cb = cubics.get(p);
                    int k = (ci < 0) ? RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], got, 0, T_A, T_B, 0)
                                     : CapSweep.solve(cb[0], cb[1], cb[2], cb[3], got, 0);
                    boolean[] used = new boolean[Math.max(k, 1)];
                    for (double ts : trueRoots.get(p)) {
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ts) < bd) { bd = Math.abs(got[j] - ts); best = j; }
                        if (best < 0 || bd > 0.05) { missed++; continue; }
                        used[best] = true;
                        errs.add(posErr(cu, got[best], ts));
                    }
                }
                Collections.sort(errs);
                String name = (ci < 0) ? "original closed form" : ("bracketed cap=" + CAPS[ci]);
                System.out.printf("%-22s %10d %12.3e %12.3e %12.3e%s%n", name, missed,
                        errs.isEmpty() ? Double.NaN : errs.get(errs.size()/2),
                        errs.isEmpty() ? Double.NaN : errs.get(Math.min(errs.size()-1, (int)(errs.size()*0.9999))),
                        errs.isEmpty() ? Double.NaN : errs.get(errs.size()-1),
                        (missed == 0 && !errs.isEmpty() && errs.get(errs.size()-1) <= tol) ? "   <= 1/512 OK" : "");
            }
        }
    }

    /** |C(t) - C(ts)| in pixels, Horner with fma */
    static double posErr(double[] cu, double t, double ts) {
        double x0 = cu[0], x1 = cu[1], x2 = cu[2], x3 = cu[3];
        double y0 = cu[4], y1 = cu[5], y2 = cu[6], y3 = cu[7];
        double ax = 3.0*(x1-x2)+x3-x0, bx = 3.0*(x0-2.0*x1+x2), cx = 3.0*(x1-x0);
        double ay = 3.0*(y1-y2)+y3-y0, by = 3.0*(y0-2.0*y1+y2), cy = 3.0*(y1-y0);
        double dx = Math.fma(Math.fma(Math.fma(ax, t, bx), t, cx), t, x0)
                  - Math.fma(Math.fma(Math.fma(ax, ts, bx), ts, cx), ts, x0);
        double dy = Math.fma(Math.fma(Math.fma(ay, t, by), t, cy), t, y0)
                  - Math.fma(Math.fma(Math.fma(ay, ts, by), ts, cy), ts, y0);
        return Math.hypot(dx, dy);
    }
}
