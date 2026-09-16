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
 * Candidate A: put xPoints/yPoints (findClipPoints) back on the plain closed form.
 *   Curve.xPoints solves x(t) = clip edge over [0,1). Clip rect 0..4096 by 0..3000,
 *   curves in [-100,4200] by [-100,3000] so they cross the edges.
 *
 * Candidate B: drop the Newton step from quadraticRoots (findSubdivPoints' dxRoots,
 *   dyRoots and infPoints), keeping Kahan's discriminant.
 *
 * Both scored in pixels against 1/256 = 3.906e-3 px, which is what Marlin needs.
 */
public class TestBoth {
    static Random rnd;
    static double coord(double lo, double hi) {
        double v = lo + (hi - lo) * rnd.nextDouble();
        return Math.rint(v * 256.0) / 256.0;          // 1/256 quantised
    }
    static final double tol = 1.0 / 256.0;

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 200000;

        // ---------------------------------------------------------------- Candidate A
        System.out.println("CANDIDATE A: xPoints / yPoints for findClipPoints, over [0,1)");
        System.out.printf("%-24s %8s %8s %12s %12s %10s %9s%n", "solver", "roots", "missed", "median px", "max px", "> 1/256", "ns/solve");
        double[] clipX = { 0.0, 4096.0 }, clipY = { 0.0, 3000.0 };
        ArrayList<double[]> cvs = new ArrayList<>(), cbs = new ArrayList<>(), rfs = new ArrayList<>();
        rnd = new Random(77177L);
        double[] rv = new double[8];
        while (cvs.size() < N) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = coord(-100, 4200); Y[j] = coord(-100, 3000); }
            boolean useX = rnd.nextBoolean();
            double[] P = useX ? X : Y;
            double edge = useX ? clipX[rnd.nextInt(2)] : clipY[rnd.nextInt(2)];
            double a3 = 3.0*(P[1]-P[2])+P[3]-P[0], b3 = 3.0*(P[0]-2.0*P[1]+P[2]), c3 = 3.0*(P[1]-P[0]);
            double[] co = { a3, b3, c3, P[0] - edge };
            if (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) continue;
            CapProper.A = 0.0; CapProper.B = 1.0; CapProper.CAP = 200; CapProper.REL = 0.0;
            int kr = CapProper.solve(co[0], co[1], co[2], co[3], rv, 0);
            if (kr == 0) continue;
            // the curve used for the position error is the 2D curve, but xPoints only
            // constrains one axis; the split point moves along the whole curve, so use both
            cvs.add(new double[]{X[0],X[1],X[2],X[3],Y[0],Y[1],Y[2],Y[3]});
            cbs.add(co);
            double[] rr = new double[kr];
            System.arraycopy(rv, 0, rr, 0, kr);
            rfs.add(rr);
        }
        long totA = 0; for (double[] r : rfs) totA += r.length;
        for (int s = 0; s < 2; s++) {
            double[] got = new double[8];
            ArrayList<Double> errs = new ArrayList<>();
            long missed = 0, over = 0;
            for (int p = 0; p < cbs.size(); p++) {
                double[] cb = cbs.get(p), cu = cvs.get(p);
                int k = (s == 0) ? RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], got, 0, 0.0, 1.0, 0)
                                 : FinalHelpers.cubicRootsInAB(cb[0], cb[1], cb[2], cb[3], got, 0, 0.0, 1.0);
                boolean[] used = new boolean[Math.max(k,1)];
                for (double ts : rfs.get(p)) {
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
            System.out.printf("%-24s %8d %8d %12.3e %12.3e %10d %9d%n",
                    s == 0 ? "plain closed form" : "shipped hybrid", totA, missed,
                    errs.get(errs.size()/2), errs.get(errs.size()-1), over, timeA(cbs, s));
        }

        // ---------------------------------------------------------------- Candidate B
        System.out.println("\nCANDIDATE B: quadraticRoots for findSubdivPoints (dxRoots, dyRoots, infPoints)");
        System.out.printf("%-30s %8s %8s %12s %12s %10s %12s%n", "solver", "roots", "missed", "median px", "max px", "> 1/256", "ns/3 quads");
        ArrayList<double[]> qc = new ArrayList<>(), qcv = new ArrayList<>(), qr = new ArrayList<>();
        rnd = new Random(88288L);
        while (qcv.size() < N) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = coord(-100, 4200); Y[j] = coord(-100, 3000); }
            double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
            double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
            double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
            double ia = dax*dby - dbx*day, ib = 2.0*(cy*dax - day*cx), ic = cy*dbx - cx*dby;
            // three quadratics per curve, each (a,b,c)
            qc.add(new double[]{ dax, dbx, cx, day, dby, cy, ia, ib, ic });
            qcv.add(new double[]{X[0],X[1],X[2],X[3],Y[0],Y[1],Y[2],Y[3]});
            ArrayList<Double> rs = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                double a = qc.get(qc.size()-1)[3*t], b = qc.get(qc.size()-1)[3*t+1], c = qc.get(qc.size()-1)[3*t+2];
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(0.0, a, b, c);
                if (refs == null) continue;
                for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= 1e-4 && ed < 1.0-1e-4) rs.add(ed); }
            }
            double[] rr = new double[rs.size()];
            for (int i = 0; i < rr.length; i++) rr[i] = rs.get(i);
            qr.add(rr);
        }
        long totB = 0; for (double[] r : qr) totB += r.length;
        for (int s = 0; s < 2; s++) {
            double[] got = new double[8];
            ArrayList<Double> errs = new ArrayList<>();
            long missed = 0, over = 0;
            for (int p = 0; p < qc.size(); p++) {
                double[] Q = qc.get(p), cu = qcv.get(p);
                int k = 0;
                for (int t = 0; t < 3; t++) {
                    k += (s == 0) ? QuadNewton.solve(Q[3*t], Q[3*t+1], Q[3*t+2], got, k, 0)
                                  : FinalHelpers.quadraticRoots(Q[3*t], Q[3*t+1], Q[3*t+2], got, k);
                }
                k = FinalHelpers.filterOutNotInAB(got, 0, k, 1e-4, 1.0-1e-4);
                boolean[] used = new boolean[Math.max(k,1)];
                for (double ts : qr.get(p)) {
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
            System.out.printf("%-30s %8d %8d %12.3e %12.3e %10d %12d%n",
                    s == 0 ? "Kahan only, no Newton" : "shipped (Kahan + Newton)", totB, missed,
                    errs.get(errs.size()/2), errs.get(errs.size()-1), over, timeB(qc, s));
        }
    }
    static long timeA(List<double[]> cbs, int s) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int x = 0;
            for (double[] cb : cbs) x += (s == 0) ? RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], pts, 0, 0.0, 1.0, 0)
                                                  : FinalHelpers.cubicRootsInAB(cb[0], cb[1], cb[2], cb[3], pts, 0, 0.0, 1.0);
            long ns = (System.nanoTime()-t0)/cbs.size();
            if (ns < best) best = ns;
        }
        return best;
    }
    static long timeB(List<double[]> qc, int s) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int x = 0;
            for (double[] Q : qc) {
                int k = 0;
                for (int t = 0; t < 3; t++)
                    k += (s == 0) ? QuadNewton.solve(Q[3*t], Q[3*t+1], Q[3*t+2], pts, k, 0)
                                  : FinalHelpers.quadraticRoots(Q[3*t], Q[3*t+1], Q[3*t+2], pts, k);
                x += k;
            }
            long ns = (System.nanoTime()-t0)/qc.size();
            if (ns < best) best = ns;
        }
        return best;
    }
}
