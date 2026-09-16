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
 * Determine the cap properly, with a bigger sample, and test whether a relative-step
 * convergence exit lets it converge in the 20-30 range the way it should.
 *
 * The shipped loop exits only on nt == t or bracket exhaustion, so it keeps stepping long
 * after the root is good enough. Newton's own step is an estimate of the remaining error,
 * so stopping when |nt - t| <= REL * |t| stops at a stated precision: REL = 1e-11 is four
 * orders tighter than the 1.3e-7 that 1/512 px needs, and is reached in about five steps
 * when convergence is quadratic and in about twenty-seven when it is linear, which is the
 * ill-conditioned case.
 */
public class CapProper {
    static double A = 1e-6, B = 1.0 - 1e-6;
    public static int CAP = 48;
    public static double REL = 0.0;                  // 0 disables the exit
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
            if (f == 0.0) break;
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            boolean newtonOk = Double.isFinite(nt) && nt > lo && nt < hi;
            if ((f < 0.0) == negLo) lo = t; else hi = t;
            if (!newtonOk || nt <= lo || nt >= hi) {
                nt = 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) break;
            } else if (REL > 0.0 && Math.abs(nt - t) <= REL * Math.abs(t)) {
                // Newton step below the target precision: take it and stop
                t = nt;
                double fn = FinalHelpers.compHorner(d, a, b, c, t);
                iters++;
                if (Math.abs(fn) < fbest) best = t;
                break;
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

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 60000;

        // ---- big 20-decade sample: loss vs cap, with and without the step exit
        BracketSolve.rnd = new Random(20260915L);
        ArrayList<double[]> ps = new ArrayList<>(), rs = new ArrayList<>();
        while (ps.size() < N) {
            double[] co = BracketSolve.shape(2);          // 20 decades
            if (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) continue;
            List<BigDecimal> refs = RootsUlpEval2.referenceRoots(co[0], co[1], co[2], co[3]);
            if (refs == null) continue;
            ArrayList<Double> in = new ArrayList<>();
            for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) in.add(ed); }
            ps.add(co);
            double[] rr = new double[in.size()];
            for (int j = 0; j < rr.length; j++) rr[j] = in.get(j);
            rs.add(rr);
        }
        long tot = 0; for (double[] rr : rs) tot += rr.length;
        System.out.printf("20-decade sample: %d polynomials, %d roots in [A,B)%n%n", ps.size(), tot);
        System.out.printf("%5s | %-26s | %-26s%n", "cap", "no step exit", "step exit REL=1e-11");
        System.out.printf("%5s | %8s %8s %7s | %8s %8s %7s%n", "", "lost", "iters", "ns", "lost", "iters", "ns");
        for (int cap : new int[]{ 12, 16, 20, 24, 28, 32, 40, 48 }) {
            StringBuilder row = new StringBuilder(String.format("%5d |", cap));
            for (double rel : new double[]{ 0.0, 1e-11 }) {
                CAP = cap; REL = rel; iters = 0; roots = 0;
                long lost = score(ps, rs);
                double ipr = iters / (double) Math.max(1, roots);
                long ns = time(ps);
                row.append(String.format(" %8d %8.2f %7d |", lost, ipr, ns));
            }
            System.out.println(row);
        }

        // ---- the original, on the same sample
        long lostO = 0;
        double[] got = new double[8];
        for (int p = 0; p < ps.size(); p++) {
            double[] co = ps.get(p);
            int k = RootsUlpEval3.cubicImpl(co[0], co[1], co[2], co[3], got, 0, A, B, 0);
            boolean[] used = new boolean[Math.max(k,1)];
            for (double ed : rs.get(p)) {
                int best = -1; double bd = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                if (best < 0 || bd > 1e-6) lostO++; else used[best] = true;
            }
        }
        System.out.printf("%noriginal closed form on the same sample: lost %d of %d, %d ns/solve%n", lostO, tot, timeOrig(ps));
    }

    static long score(List<double[]> ps, List<double[]> rs) {
        double[] got = new double[8];
        long lost = 0;
        for (int p = 0; p < ps.size(); p++) {
            double[] co = ps.get(p);
            int k = solve(co[0], co[1], co[2], co[3], got, 0);
            boolean[] used = new boolean[Math.max(k,1)];
            for (double ed : rs.get(p)) {
                int best = -1; double bd = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                if (best < 0 || bd > 1e-6) lost++; else used[best] = true;
            }
        }
        return lost;
    }
    static long time(List<double[]> ps) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            long t0 = System.nanoTime(); int s = 0;
            for (int r = 0; r < 8; r++) for (double[] co : ps) s += solve(co[0], co[1], co[2], co[3], pts, 0);
            long ns = (System.nanoTime()-t0)/8/ps.size();
            if (ns < best) best = ns;
        }
        return best;
    }
    static long timeOrig(List<double[]> ps) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            long t0 = System.nanoTime(); int s = 0;
            for (int r = 0; r < 8; r++) for (double[] co : ps) s += RootsUlpEval3.cubicImpl(co[0], co[1], co[2], co[3], pts, 0, A, B, 0);
            long ns = (System.nanoTime()-t0)/8/ps.size();
            if (ns < best) best = ns;
        }
        return best;
    }
}
