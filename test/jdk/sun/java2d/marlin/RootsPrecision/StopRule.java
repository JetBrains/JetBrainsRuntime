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
 * Can a high cap (robust for 40-decade input) be kept while paying only the iterations
 * that actually improve the root? The wasted ones are the loop not knowing it is done:
 * f(t*) at the correctly rounded root is still a few ulps of the evaluation scale, so
 * Newton keeps stepping.
 *   rule 0 = none (cap alone, as shipped)
 *   rule 1 = stop when |f| reaches the evaluation noise floor, 4 ulp of the largest term
 *   rule 2 = stop when |f| stops improving (cheapest: no extra arithmetic)
 */
public class StopRule {
    public static double A = 1e-4, B = 1.0 - 1e-4;
    public static int CAP = 90, RULE = 0;
    public static long iters = 0, roots = 0;

    static double refine(double d, double a, double b, double c, double lo, double hi, double flo, double fhi) {
        double t = lo - flo * (hi - lo) / (fhi - flo);
        if (!(t > lo && t < hi)) t = 0.5 * (lo + hi);
        double best = t, fbest = Double.POSITIVE_INFINITY;
        boolean negLo = flo < 0.0;
        for (int it = 0; it < CAP; it++) {
            iters++;
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            else if (RULE == 2) break;                       // residual stopped improving
            if (f == 0.0) break;
            if (RULE == 1) {
                double t2 = t * t;
                double scale = Math.max(Math.max(Math.abs(d * t2 * t), Math.abs(a * t2)),
                                        Math.max(Math.abs(b * t), Math.abs(c)));
                if (af <= 4.0 * Math.ulp(scale)) break;      // nothing left to resolve
            } else if (RULE == 3 || RULE == 4) {
                // residual floor AND the bracket narrowed to the precision we need
                double t2 = t * t;
                double scale = Math.max(Math.max(Math.abs(d * t2 * t), Math.abs(a * t2)),
                                        Math.max(Math.abs(b * t), Math.abs(c)));
                double want = (RULE == 3) ? 1e-9 : 1e-12;
                if (af <= 4.0 * Math.ulp(scale) && (hi - lo) <= want) break;
            }
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            if ((f < 0.0) == negLo) lo = t; else hi = t;
            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                nt = 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) break;
            }
            if (nt == t) break;
            t = nt;
        }
        roots++;
        return best;
    }

    public static int solve(double d, double a, double b, double c, double[] pts, int off) {
        if (d == 0.0) {
            int n = FinalHelpers.quadraticRoots(a, b, c, pts, off);
            return FinalHelpers.filterOutNotInAB(pts, off, n, A, B) - off;
        }
        double[] cp = new double[2];
        int ncp = FinalHelpers.criticalPoints(d, a, b, cp);
        double[] bnd = new double[4];
        int nb = 0; bnd[nb++] = A;
        for (int i = 0; i < ncp; i++) if (cp[i] > A && cp[i] < B && cp[i] > bnd[nb-1]) bnd[nb++] = cp[i];
        bnd[nb++] = B;
        int num = 0;
        double flo = FinalHelpers.compHorner(d, a, b, c, bnd[0]);
        if (flo == 0.0) pts[off + num++] = bnd[0];
        for (int i = 0; i + 1 < nb; i++) {
            double lo = bnd[i], hi = bnd[i+1];
            double fhi = FinalHelpers.compHorner(d, a, b, c, hi);
            if (flo != 0.0 && fhi != 0.0 && ((flo < 0.0) != (fhi < 0.0))) {
                double t = refine(d, a, b, c, lo, hi, flo, fhi);
                if (t >= A && t < B && num < 3) pts[off + num++] = t;
            } else if (fhi == 0.0 && hi < B && num < 3) pts[off + num++] = hi;
            flo = fhi;
        }
        for (int i = 0; i < ncp && num < 3; i++) {
            double tc = cp[i];
            if (tc < A || tc >= B || FinalHelpers.compHorner(d, a, b, c, tc) != 0.0) continue;
            boolean dup = false;
            for (int j = 0; j < num; j++) if (pts[off+j] == tc) dup = true;
            if (!dup) pts[off + num++] = tc;
        }
        return num;
    }

    public static Random rnd;
    public static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double mag(int D) { double v = Math.pow(10.0, (rnd.nextDouble()-0.5)*D); return rnd.nextBoolean() ? v : -v; }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 15000;
        double tol = 1.0 / 512.0;

        // ---- pixel-space accuracy and cost on the ROC-cusp cubic
        System.out.printf("%-26s %8s %10s %12s %12s %10s%n", "stop rule (cap 90)", "missed", "iters/root", "max px err", "ns/solve", "1/512?");
        rnd = new Random(8080811L);
        ArrayList<double[]> curves = new ArrayList<>(), cubics = new ArrayList<>(), tr = new ArrayList<>();
        while (curves.size() < N) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = uni(0, 4096); Y[j] = uni(0, 4096); }
            double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
            double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
            double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
            double pa = 2.0*(dax*dax+day*day), pb = 3.0*(dax*dbx+day*dby);
            double pc = 2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, pd = dbx*cx+dby*cy;
            if (pa == 0.0 || !Double.isFinite(pa+pb+pc+pd)) continue;
            List<BigDecimal> refs = RootsUlpEval2.referenceRoots(pa, pb, pc, pd);
            if (refs == null) continue;
            ArrayList<Double> in = new ArrayList<>();
            for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) in.add(ed); }
            if (in.isEmpty()) continue;
            curves.add(new double[]{X[0],X[1],X[2],X[3],Y[0],Y[1],Y[2],Y[3]});
            cubics.add(new double[]{pa,pb,pc,pd});
            double[] rr = new double[in.size()]; for (int i=0;i<rr.length;i++) rr[i]=in.get(i);
            tr.add(rr);
        }
        for (int rule : new int[]{ 0, 1, 2 }) {
            RULE = rule; CAP = 90; iters = 0; roots = 0;
            double[] got = new double[8];
            double maxErr = 0; long missed = 0;
            for (int p = 0; p < curves.size(); p++) {
                double[] cu = curves.get(p), cb = cubics.get(p);
                int k = solve(cb[0], cb[1], cb[2], cb[3], got, 0);
                boolean[] used = new boolean[Math.max(k,1)];
                for (double ts : tr.get(p)) {
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ts) < bd) { bd = Math.abs(got[j]-ts); best = j; }
                    if (best < 0 || bd > 0.05) { missed++; continue; }
                    used[best] = true;
                    maxErr = Math.max(maxErr, PixelCap.posErr(cu, got[best], ts));
                }
            }
            double ipr = iters / (double) roots;
            long ns = time(cubics);
            System.out.printf("%-26s %8d %10.2f %12.3e %12d %10s%n",
                    rule == 0 ? "none (shipped)" : rule == 1 ? "residual noise floor" : "no improvement",
                    missed, ipr, maxErr, ns, (missed == 0 && maxErr <= tol) ? "OK" : "FAIL");
        }

        // ---- does the stop rule keep the 40-decade guarantee?
        System.out.printf("%n%-26s %10s %10s %10s%n", "stop rule (cap 90)", "decades", "lost", "<=0.5ulp");
        for (int rule : new int[]{ 0, 1, 2 }) {
            RULE = rule; CAP = 90;
            for (int D : new int[]{ 20, 40 }) {
                rnd = new Random(55500L + D);
                long lost = 0, ok = 0, tot = 0;
                double[] got = new double[8];
                for (int i = 0; i < N; i++) {
                    double d = mag(D), a = mag(D), b = mag(D), c = mag(D);
                    if (d == 0.0 || !Double.isFinite(d+a+b+c)) continue;
                    List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                    if (refs == null) continue;
                    int k = solve(d, a, b, c, got, 0);
                    boolean[] used = new boolean[Math.max(k,1)];
                    for (BigDecimal e : refs) {
                        double ed = e.doubleValue();
                        if (ed < A || ed >= B) continue;
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                        if (best < 0 || bd > 1e-6) { lost++; continue; }
                        used[best] = true; tot++;
                        if (e.subtract(new BigDecimal(got[best]), RootsUlpEval2.MC).abs().doubleValue()/Math.ulp(ed) <= 0.5) ok++;
                    }
                }
                System.out.printf("%-26s %10d %10d %9.2f%%%n",
                        rule == 0 ? "none (shipped)" : rule == 1 ? "residual noise floor" : "no improvement",
                        D, lost, tot == 0 ? Double.NaN : 100.0*ok/tot);
            }
        }
    }
    static long time(List<double[]> cubics) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int sink = 0;
            for (int r = 0; r < 40; r++) for (double[] cb : cubics) sink += solve(cb[0], cb[1], cb[2], cb[3], pts, 0);
            long ns = (System.nanoTime()-t0)/40/cubics.size();
            if (ns < best) best = ns;
        }
        return best;
    }
}
