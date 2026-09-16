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
 * Optimal cap for: coefficients up to 20 decades of spread, subdivision points accurate
 * to 2e-3 px (1/512). Two criteria, whichever binds:
 *   robustness - no lost root at 20-decade spread
 *   accuracy   - max |C(t) - C(t*)| <= 2e-3 px at device scale
 */
public class Converge {
    static final double A = 1e-4, B = 1.0 - 1e-4;     // Helpers' T_A / T_B
    static final int[] CAPS = { 12, 16, 20, 22, 24, 26, 28, 32, 40, 90 };
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double mag20() { double v = Math.pow(10.0, (rnd.nextDouble() - 0.5) * 20); return rnd.nextBoolean() ? v : -v; }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 25000;
        double tolPx = 1.0 / 512.0;
        CapSweep.A = A; CapSweep.B = B;

        // ---------------- robustness: 20-decade independent coefficients
        rnd = new Random(31415926L);
        ArrayList<double[]> wc = new ArrayList<>(), wr = new ArrayList<>();
        while (wc.size() < N) {
            double d = mag20(), a = mag20(), b = mag20(), c = mag20();
            if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
            List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
            if (refs == null) continue;
            ArrayList<Double> in = new ArrayList<>();
            for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) in.add(ed); }
            wc.add(new double[]{ d, a, b, c });
            double[] rr = new double[in.size()];
            for (int i = 0; i < rr.length; i++) rr[i] = in.get(i);
            wr.add(rr);
        }
        long wTot = 0; for (double[] rr : wr) wTot += rr.length;

        // ---------------- accuracy: device-scale curves, both Marlin cubics
        rnd = new Random(27182818L);
        ArrayList<double[]> cvs = new ArrayList<>(), pcb = new ArrayList<>(), pr = new ArrayList<>();
        while (cvs.size() < N) {
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
            cvs.add(new double[]{X[0],X[1],X[2],X[3],Y[0],Y[1],Y[2],Y[3]});
            pcb.add(new double[]{pa,pb,pc,pd});
            double[] rr = new double[in.size()];
            for (int i = 0; i < rr.length; i++) rr[i] = in.get(i);
            pr.add(rr);
        }
        long pTot = 0; for (double[] rr : pr) pTot += rr.length;

        System.out.printf("20-decade sample: %d polys, %d roots | device-scale sample: %d curves, %d ROC-cusp roots%n",
                wc.size(), wTot, cvs.size(), pTot);
        System.out.printf("%n%5s | %12s %12s | %12s %12s | %10s%n", "cap",
                "20dec lost", "20dec <=.5ulp", "px max err", "px missed", "ns/solve");
        double[] got = new double[8];
        for (int cap : CAPS) {
            CapSweep.CAP = cap;
            long lost = 0, ok = 0, tot = 0;
            for (int p = 0; p < wc.size(); p++) {
                double[] co = wc.get(p);
                int k = CapSweep.solve(co[0], co[1], co[2], co[3], got, 0);
                boolean[] used = new boolean[Math.max(k, 1)];
                for (double ed : wr.get(p)) {
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                    if (best < 0 || bd > 1e-6) { lost++; continue; }
                    used[best] = true; tot++;
                    if (Math.abs(got[best] - ed) <= 0.5 * Math.ulp(ed)) ok++;
                }
            }
            double maxPx = 0; long missed = 0;
            for (int p = 0; p < cvs.size(); p++) {
                double[] cu = cvs.get(p), cb = pcb.get(p);
                int k = CapSweep.solve(cb[0], cb[1], cb[2], cb[3], got, 0);
                boolean[] used = new boolean[Math.max(k, 1)];
                for (double ts : pr.get(p)) {
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ts) < bd) { bd = Math.abs(got[j]-ts); best = j; }
                    if (best < 0 || bd > 0.05) { missed++; continue; }
                    used[best] = true;
                    maxPx = Math.max(maxPx, PixelCap.posErr(cu, got[best], ts));
                }
            }
            long ns = time(pcb);
            System.out.printf("%5d | %12d %11.2f%% | %12.3e %12d | %10d%s%n", cap, lost,
                    tot == 0 ? Double.NaN : 100.0*ok/tot, maxPx, missed, ns,
                    (lost == 0 && missed == 0 && maxPx <= tolPx) ? "   both OK" : "");
        }
        System.out.printf("%nfor reference, original closed form: ");
        long lostO = 0; double maxPxO = 0; long missedO = 0;
        for (int p = 0; p < wc.size(); p++) {
            double[] co = wc.get(p);
            int k = RootsUlpEval3.cubicImpl(co[0], co[1], co[2], co[3], got, 0, A, B, 0);
            boolean[] used = new boolean[Math.max(k, 1)];
            for (double ed : wr.get(p)) {
                int best = -1; double bd = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                if (best < 0 || bd > 1e-6) lostO++; else used[best] = true;
            }
        }
        for (int p = 0; p < cvs.size(); p++) {
            double[] cu = cvs.get(p), cb = pcb.get(p);
            int k = RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], got, 0, A, B, 0);
            boolean[] used = new boolean[Math.max(k, 1)];
            for (double ts : pr.get(p)) {
                int best = -1; double bd = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ts) < bd) { bd = Math.abs(got[j]-ts); best = j; }
                if (best < 0 || bd > 0.05) { missedO++; continue; }
                used[best] = true;
                maxPxO = Math.max(maxPxO, PixelCap.posErr(cu, got[best], ts));
            }
        }
        System.out.printf("20dec lost=%d of %d | px max err=%.3e | px missed=%d | ns=%d%n",
                lostO, wTot, maxPxO, missedO, timeOrig(pcb));
    }
    static long time(List<double[]> cs) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int sink = 0;
            for (int r = 0; r < 25; r++) for (double[] cb : cs) sink += CapSweep.solve(cb[0], cb[1], cb[2], cb[3], pts, 0);
            long ns = (System.nanoTime()-t0)/25/cs.size();
            if (ns < best) best = ns;
        }
        return best;
    }
    static long timeOrig(List<double[]> cs) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int sink = 0;
            for (int r = 0; r < 25; r++) for (double[] cb : cs) sink += RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], pts, 0, A, B, 0);
            long ns = (System.nanoTime()-t0)/25/cs.size();
            if (ns < best) best = ns;
        }
        return best;
    }
}
