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
 * Fast path by VERIFICATION rather than prediction.
 *
 * No cheap function of the coefficients separates the safe cases from the unsafe: the
 * closed form's two failure modes have opposite signatures (large shift with condition 1,
 * versus shift exactly 1 with condition 1e11), and any threshold leaks about 1% of them.
 * But the sign scan over the monotone pieces of f is needed anyway and gives the exact
 * root count, so the closed form's answer can be CHECKED instead of trusted:
 *
 *   for each bracket with a sign change:
 *     if exactly one closed-form root lies strictly inside
 *        and one Newton step moves it by <= REFINE_EPS * |t|   -> accept it
 *     else                                                     -> solveBracket the bracket
 *
 * Acceptance requires demonstrated convergence, so an unforeseen input class falls back
 * rather than being silently wrong.
 */
public class Hybrid {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static final double REFINE_EPS = 1e-11;
    static long brackets = 0, fellBack = 0;
    static int GEN = 0;   // 0 = fma closed form, 1 = plain original closed form

    static int solve(double d, double a, double b, double c, double[] pts, int off) {
        if (d == 0.0) {
            int n = FinalHelpers.quadraticRoots(a, b, c, pts, off);
            return FinalHelpers.filterOutNotInAB(pts, off, n, A, B) - off;
        }
        // closed-form candidates, unfiltered
        double[] cand = new double[4];
        int nc = (GEN == 0)
                ? EFTSolve.solve(d, a, b, c, cand, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 4)
                : RootsUlpEval3.cubicImpl(d, a, b, c, cand, 0, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0);

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
                brackets++;
                // exactly one candidate strictly inside?
                int found = 0; double t = Double.NaN;
                for (int j = 0; j < nc; j++) if (cand[j] > lo && cand[j] < hi) { found++; t = cand[j]; }
                boolean ok = false;
                if (found == 1) {
                    double f = FinalHelpers.compHorner(d, a, b, c, t);
                    double fp = Math.fma(Math.fma(3.0*d, t, 2.0*a), t, b);
                    if (f == 0.0) ok = true;
                    else if (fp != 0.0) {
                        double nt = t - f / fp;
                        if (Double.isFinite(nt) && Math.abs(nt - t) <= REFINE_EPS * Math.abs(t)) {
                            double fn = FinalHelpers.compHorner(d, a, b, c, nt);
                            if (Math.abs(fn) <= Math.abs(f)) t = nt;
                            ok = true;
                        }
                    }
                }
                if (!ok) { fellBack++; t = FinalHelpers.solveBracket(d, a, b, c, lo, hi, flo, lo - flo*(hi-lo)/(fhi-flo)); }
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
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 40000;
        String[] shapes = { "perpendiculardfddf device", "xPoints device", "20-decade coeffs",
                            "perp 1e-7..1e30", "40-decade coeffs", "close pair 1e-6" };
        System.out.printf("%-26s %-9s %8s %8s %8s %11s %10s%n", "shape", "generator", "roots", "lost", "spur",
                "fallback %", "ns");
        for (int sh = 0; sh < shapes.length; sh++) {
          for (int gen = 0; gen < 2; gen++) {
            GEN = gen;
            List<double[]> ps = new ArrayList<>(), rs = new ArrayList<>();
            build(sh, N, ps, rs);
            long tot = 0; for (double[] r : rs) tot += r.length;
            brackets = 0; fellBack = 0;
            long lost = 0, spur = 0;
            double[] got = new double[8];
            for (int p = 0; p < ps.size(); p++) {
                double[] co = ps.get(p);
                int k = solve(co[0], co[1], co[2], co[3], got, 0);
                boolean[] used = new boolean[Math.max(k,1)];
                for (double ed : rs.get(p)) {
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                    if (best < 0 || bd > 1e-6) lost++; else used[best] = true;
                }
                for (int j = 0; j < k; j++) {
                    if (used[j]) continue;
                    boolean near = false;
                    for (double ed : rs.get(p)) if (Math.abs(got[j]-ed) <= 1e-6) near = true;
                    if (!near) spur++;
                }
            }
            double fb = brackets == 0 ? 0 : 100.0 * fellBack / brackets;
            System.out.printf("%-26s %-9s %8d %8d %8d %10.3f%% %10d%n", gen == 0 ? shapes[sh] : "",
                    gen == 0 ? "fma" : "plain", tot, lost, spur, fb, time(ps, true));
          }
        }
    }
    static void build(int sh, int N, List<double[]> ps, List<double[]> rs) {
        BracketSolve.rnd = new Random(556677L);
        Random r = new Random(556677L);
        double[] refv = new double[8];
        for (int i = 0; i < N; i++) {
            double[] co;
            if (sh == 0 || sh == 1) {
                double[] X = new double[4], Y = new double[4];
                for (int j = 0; j < 4; j++) { X[j] = r.nextDouble()*4096; Y[j] = r.nextDouble()*4096; }
                double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
                double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
                if (sh == 1) {
                    double lo = Math.min(Math.min(X[0],X[1]),Math.min(X[2],X[3])), hi = Math.max(Math.max(X[0],X[1]),Math.max(X[2],X[3]));
                    co = new double[]{ ax, bx, cx, X[0] - (lo + (hi-lo)*r.nextDouble()) };
                } else {
                    double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
                    co = new double[]{ 2.0*(dax*dax+day*day), 3.0*(dax*dbx+day*dby),
                                       2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, dbx*cx+dby*cy };
                }
            } else if (sh == 2) co = BracketSolve.shape(2);
            else if (sh == 3) co = BracketSolve.shape(5);
            else if (sh == 4) co = BracketSolve.shape(0);
            else co = BracketSolve.shape(7);
            if (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) continue;
            CapProper.CAP = 200; CapProper.REL = 0.0;
            int kr = CapProper.solve(co[0], co[1], co[2], co[3], refv, 0);
            ps.add(co);
            double[] rr = new double[kr];
            System.arraycopy(refv, 0, rr, 0, kr);
            rs.add(rr);
        }
    }
    static long time(List<double[]> ps, boolean hybrid) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int s = 0;
            for (int r = 0; r < 12; r++) for (double[] co : ps)
                s += hybrid ? solve(co[0], co[1], co[2], co[3], pts, 0)
                            : FinalHelpers.cubicRootsInAB(co[0], co[1], co[2], co[3], pts, 0, A, B);
            long ns = (System.nanoTime()-t0)/12/ps.size();
            if (ns < best) best = ns;
        }
        return best;
    }
}
