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
 * The real domain: curves clipped to device space, x in [-100,4200], y in [-100,3000],
 * coordinates quantised to 1/256, accuracy wanted to 1/256 px.
 *
 * Reference roots from the converged bracket run (cap 200, no early exit), validated
 * earlier against the 60-digit BigDecimal reference. Position error is measured directly
 * as |C(t) - C(t*)| in pixels.
 */
public class DeviceBox {
    static final double T_A = 1e-4, T_B = 1.0 - 1e-4;
    static Random rnd;
    static double coord(double lo, double hi, boolean quant) {
        double v = lo + (hi - lo) * rnd.nextDouble();
        return quant ? Math.rint(v * 256.0) / 256.0 : v;
    }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 200000;
        double tol = 1.0 / 256.0;
        for (int q = 0; q < 2; q++) {
            boolean quant = (q == 1);
            for (int which = 0; which < 2; which++) {     // 0 = perpendiculardfddf, 1 = xPoints
                rnd = new Random(30303L);
                ArrayList<double[]> curves = new ArrayList<>(), cubics = new ArrayList<>(), refs = new ArrayList<>();
                double[] rv = new double[8];
                while (curves.size() < N) {
                    double[] X = new double[4], Y = new double[4];
                    for (int j = 0; j < 4; j++) { X[j] = coord(-100, 4200, quant); Y[j] = coord(-100, 3000, quant); }
                    double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
                    double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
                    double[] co;
                    if (which == 1) {
                        double lo = Math.min(Math.min(X[0],X[1]),Math.min(X[2],X[3])), hi = Math.max(Math.max(X[0],X[1]),Math.max(X[2],X[3]));
                        co = new double[]{ ax, bx, cx, X[0] - (lo + (hi-lo)*rnd.nextDouble()) };
                    } else {
                        double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
                        co = new double[]{ 2.0*(dax*dax+day*day), 3.0*(dax*dbx+day*dby),
                                           2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, dbx*cx+dby*cy };
                    }
                    if (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) continue;
                    CapProper.A = T_A; CapProper.B = T_B; CapProper.CAP = 200; CapProper.REL = 0.0;
                    int kr = CapProper.solve(co[0], co[1], co[2], co[3], rv, 0);
                    curves.add(new double[]{X[0],X[1],X[2],X[3],Y[0],Y[1],Y[2],Y[3]});
                    cubics.add(co);
                    double[] rr = new double[kr];
                    System.arraycopy(rv, 0, rr, 0, kr);
                    refs.add(rr);
                }
                long tot = 0; for (double[] r : refs) tot += r.length;
                System.out.printf("%n#### %s, x in [-100,4200] y in [-100,3000]%s : %d curves, %d roots%n",
                        which == 0 ? "perpendiculardfddf" : "xPoints", quant ? ", quantised to 1/256" : "", N, tot);
                System.out.printf("%-22s %8s %12s %12s %12s %10s %9s%n", "solver", "missed", "median px", "p99.99 px", "max px", "> 1/256", "ns/solve");
                for (int s = 0; s < 2; s++) {         // 0 = original, 1 = shipped hybrid
                    double[] got = new double[8];
                    ArrayList<Double> errs = new ArrayList<>();
                    long missed = 0, over = 0;
                    for (int p = 0; p < cubics.size(); p++) {
                        double[] cb = cubics.get(p), cu = curves.get(p);
                        int k = (s == 0) ? RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], got, 0, T_A, T_B, 0)
                                         : FinalHelpers.cubicRootsInAB(cb[0], cb[1], cb[2], cb[3], got, 0, T_A, T_B);
                        boolean[] used = new boolean[Math.max(k,1)];
                        for (double ts : refs.get(p)) {
                            int best = -1; double bd = Double.MAX_VALUE;
                            for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ts) < bd) { bd = Math.abs(got[j]-ts); best = j; }
                            if (best < 0 || bd > 0.05) { missed++; continue; }
                            used[best] = true;
                            double e = PixelCap.posErr(cu, got[best], ts);
                            errs.add(e);
                            if (e > tol) over++;
                        }
                    }
                    Collections.sort(errs);
                    System.out.printf("%-22s %8d %12.3e %12.3e %12.3e %9d %9d%n",
                            s == 0 ? "original closed form" : "shipped hybrid", missed,
                            errs.get(errs.size()/2), errs.get(Math.min(errs.size()-1,(int)(errs.size()*0.9999))),
                            errs.get(errs.size()-1), over, time(cubics, s));
                }
            }
        }
    }
    static long time(List<double[]> cs, int s) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int x = 0;
            for (double[] cb : cs) x += (s == 0) ? RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], pts, 0, T_A, T_B, 0)
                                                 : FinalHelpers.cubicRootsInAB(cb[0], cb[1], cb[2], cb[3], pts, 0, T_A, T_B);
            long ns = (System.nanoTime()-t0)/cs.size();
            if (ns < best) best = ns;
        }
        return best;
    }
}
