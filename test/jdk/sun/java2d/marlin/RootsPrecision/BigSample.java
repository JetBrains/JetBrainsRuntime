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
 * Cap boundary on 1e6 samples, caps 28..40 step 2.
 *
 * BigDecimal references do not scale to 1e6, but they are not needed: the number of roots
 * comes from the sign scan over the monotone pieces, which is cap-independent, so a low cap
 * never drops a root -- it only returns it inaccurately. A converged run (cap 200, no early
 * exit) therefore serves as the reference for the VALUES. Step 1 validates that claim
 * against the 60-digit reference on a smaller sample before step 2 relies on it.
 */
public class BigSample {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static final int[] CAPS = { 28, 30, 32, 34, 36, 38, 40 };

    public static void main(String[] args) {
        int NV = args.length > 0 ? Integer.parseInt(args[0]) : 20000;      // validation size
        int N  = args.length > 1 ? Integer.parseInt(args[1]) : 1000000;    // main sample

        // ---------- step 1: is the cap-200 run a sound reference?
        System.out.println("step 1: cap-200 reference against the 60-digit BigDecimal reference");
        for (int sh : new int[]{ 2, -1 }) {
            BracketSolve.rnd = new Random(12345L);
            Random r = new Random(12345L);
            long lost = 0, ok = 0, tot = 0, polys = 0;
            double[] got = new double[8];
            for (int i = 0; i < NV; i++) {
                double[] co = (sh == 2) ? shape20() : shapePerp(r, 4096.0);
                if (co == null) continue;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(co[0], co[1], co[2], co[3]);
                if (refs == null) continue;
                polys++;
                CapProper.CAP = 200; CapProper.REL = 0.0;
                int k = CapProper.solve(co[0], co[1], co[2], co[3], got, 0);
                boolean[] used = new boolean[Math.max(k,1)];
                for (BigDecimal e : refs) {
                    double ed = e.doubleValue();
                    if (ed < A || ed >= B) continue;
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                    if (best < 0 || bd > 1e-6) { lost++; continue; }
                    used[best] = true; tot++;
                    if (Math.abs(got[best]-ed) <= 0.5*Math.ulp(ed)) ok++;
                }
            }
            System.out.printf("  %-28s %d polys, %d roots: lost=%d, within 0.5 ulp=%.2f%%%n",
                    sh == 2 ? "20-decade coefficients" : "perpendiculardfddf device", polys, tot, lost, 100.0*ok/tot);
        }

        // ---------- step 2: 1e6 samples, caps 28..40
        for (int sh : new int[]{ 2, -1 }) {
            System.out.printf("%nstep 2: %s, %d samples%n", sh == 2 ? "20-decade coefficients" : "perpendiculardfddf, device scale", N);
            BracketSolve.rnd = new Random(987654321L);
            Random r = new Random(987654321L);
            double[] cs = new double[4 * N];
            int n = 0;
            while (n < N) {
                double[] co = (sh == 2) ? shape20() : shapePerp(r, 4096.0);
                if (co == null) continue;
                cs[4*n] = co[0]; cs[4*n+1] = co[1]; cs[4*n+2] = co[2]; cs[4*n+3] = co[3];
                n++;
            }
            // reference values
            double[] ref = new double[3 * N];
            int[] nref = new int[N];
            double[] got = new double[8];
            CapProper.CAP = 200; CapProper.REL = 0.0;
            long totRoots = 0;
            for (int i = 0; i < N; i++) {
                int k = CapProper.solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], got, 0);
                nref[i] = k; totRoots += k;
                for (int j = 0; j < k; j++) ref[3*i+j] = got[j];
            }
            System.out.printf("  %d roots in [A,B)%n", totRoots);
            System.out.printf("  %5s %12s %12s %10s %10s%n", "cap", "lost", "loss rate", "iters", "ns/solve");
            for (int cap : CAPS) {
                CapProper.CAP = cap; CapProper.REL = 1e-11;
                CapProper.iters = 0; CapProper.roots = 0;
                long lost = 0;
                for (int i = 0; i < N; i++) {
                    int k = CapProper.solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], got, 0);
                    boolean[] used = new boolean[Math.max(k,1)];
                    for (int j = 0; j < nref[i]; j++) {
                        double ed = ref[3*i+j];
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int q = 0; q < k; q++) if (!used[q] && Math.abs(got[q]-ed) < bd) { bd = Math.abs(got[q]-ed); best = q; }
                        if (best < 0 || bd > 1e-6) lost++; else used[best] = true;
                    }
                }
                double ipr = CapProper.iters / (double) Math.max(1, CapProper.roots);
                long ns = time(cs, N, cap);
                System.out.printf("  %5d %12d %11.5f%% %10.2f %10d%n", cap, lost, 100.0*lost/totRoots, ipr, ns);
            }
            // original closed form on the same sample
            long lostO = 0;
            for (int i = 0; i < N; i++) {
                int k = RootsUlpEval3.cubicImpl(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], got, 0, A, B, 0);
                boolean[] used = new boolean[Math.max(k,1)];
                for (int j = 0; j < nref[i]; j++) {
                    double ed = ref[3*i+j];
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int q = 0; q < k; q++) if (!used[q] && Math.abs(got[q]-ed) < bd) { bd = Math.abs(got[q]-ed); best = q; }
                    if (best < 0 || bd > 1e-6) lostO++; else used[best] = true;
                }
            }
            System.out.printf("  %5s %12d %11.5f%% %10s %10d%n", "orig", lostO, 100.0*lostO/totRoots, "-", timeOrig(cs, N));
        }
    }

    static double[] shape20() {
        double[] co = BracketSolve.shape(2);
        return (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) ? null : co;
    }
    static double[] shapePerp(Random r, double span) {
        double[] X = new double[4], Y = new double[4];
        for (int j = 0; j < 4; j++) { X[j] = r.nextDouble()*span; Y[j] = r.nextDouble()*span; }
        double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
        double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
        double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
        double[] co = { 2.0*(dax*dax+day*day), 3.0*(dax*dbx+day*dby),
                        2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, dbx*cx+dby*cy };
        return (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) ? null : co;
    }
    static long time(double[] cs, int N, int cap) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        CapProper.CAP = cap; CapProper.REL = 1e-11;
        for (int pass = 0; pass < 2; pass++) {
            long t0 = System.nanoTime(); int s = 0;
            for (int i = 0; i < N; i++) s += CapProper.solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0);
            long ns = (System.nanoTime()-t0)/N;
            if (ns < best) best = ns;
        }
        return best;
    }
    static long timeOrig(double[] cs, int N) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            long t0 = System.nanoTime(); int s = 0;
            for (int i = 0; i < N; i++) s += RootsUlpEval3.cubicImpl(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0, A, B, 0);
            long ns = (System.nanoTime()-t0)/N;
            if (ns < best) best = ns;
        }
        return best;
    }
}
