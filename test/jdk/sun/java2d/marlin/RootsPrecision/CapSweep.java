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
/** The iteration cap is a direct cost/accuracy knob: best-|f| is tracked, so stopping
 *  early still returns the best double seen, just possibly not the correctly rounded one. */
public class CapSweep {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static int CAP = 90;
    static boolean WIDTH_EXIT = false;
    static double refine(double d, double a, double b, double c, double lo, double hi, double flo, double fhi) {
        double t = lo - flo * (hi - lo) / (fhi - flo);
        if (!(t > lo && t < hi)) t = 0.5 * (lo + hi);
        double best = t, fbest = Double.POSITIVE_INFINITY;
        boolean negLo = flo < 0.0;
        for (int it = 0; it < CAP; it++) {
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            if (f == 0.0) return t;
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            if ((f < 0.0) == negLo) lo = t; else hi = t;
            // the bracket is down to adjacent doubles: best is the root, stop grinding
            if (WIDTH_EXIT && (hi - lo) <= Math.ulp(Math.max(Math.abs(lo), Math.abs(hi)))) break;
            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                nt = 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) break;
            }
            if (nt == t) break;
            t = nt;
        }
        return best;
    }
    static int solve(double d, double a, double b, double c, double[] pts, int off) {
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
        int N = 600_000;
        Random r = new Random(7L);
        double[] cs = new double[4 * N];
        for (int i = 0; i < N; i++) {
            double x1 = r.nextDouble()*4096, x2 = r.nextDouble()*4096, x3 = r.nextDouble()*4096, x4 = r.nextDouble()*4096;
            cs[4*i] = 3.0*(x2-x3)+x4-x1; cs[4*i+1] = 3.0*(x1-2.0*x2+x3); cs[4*i+2] = 3.0*(x2-x1); cs[4*i+3] = x1 - r.nextDouble()*4096;
        }
        double[] pts = new double[8];
        int[] caps = { 90, 90 };
        System.out.printf("%6s %10s", "exit", "ns/solve");
        String[] shn = { "px", "40dec", "perp1e30" };
        for (String s : shn) System.out.printf(" | %s: lost  <=0.5ulp", s);
        System.out.println();
        for (int ci = 0; ci < caps.length; ci++) {
            CAP = caps[ci]; WIDTH_EXIT = (ci == 1);
            long ns = 0;
            for (int pass = 0; pass < 3; pass++) {
                long t0 = System.nanoTime(); int sink = 0;
                for (int i = 0; i < N; i++) sink += solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0);
                ns = (System.nanoTime() - t0) / N;
            }
            System.out.printf("%6s %10d", WIDTH_EXIT ? "width" : "none", ns);
            for (int sh : new int[]{ 6, 0, 5 }) {
                BracketSolve.rnd = new Random(777333L);
                long lost = 0, ok = 0, tot = 0;
                double[] got = new double[8];
                for (int i = 0; i < 20000; i++) {
                    double[] co = BracketSolve.shape(sh);
                    double d = co[0], a = co[1], b = co[2], c = co[3];
                    if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                    List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                    if (refs == null) continue;
                    int k = solve(d, a, b, c, got, 0);
                    boolean[] used = new boolean[Math.max(k, 1)];
                    for (BigDecimal e : refs) {
                        double ed = e.doubleValue();
                        if (ed < A || ed >= B) continue;
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ed) < bd) { bd = Math.abs(got[j] - ed); best = j; }
                        if (best < 0 || bd > 1e-6) { lost++; continue; }
                        used[best] = true; tot++;
                        if (e.subtract(new BigDecimal(got[best]), RootsUlpEval2.MC).abs().doubleValue() / Math.ulp(ed) <= 0.5) ok++;
                    }
                }
                System.out.printf(" | %4d %8.2f%%", lost, tot == 0 ? Double.NaN : 100.0 * ok / tot);
            }
            System.out.println();
        }
    }
}
