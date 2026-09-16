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
 * The bracket scan guarantees the roots regardless of the seed, so the closed form is
 * only an accelerator and its cost is negotiable. Which seeding is cheapest while still
 * returning every root correctly rounded?
 *   0 = forward + reverse closed form (as shipped)
 *   1 = forward closed form only
 *   2 = no closed form at all: safeguarded bisection from the bracket midpoint
 */
public class SeedVariants {
    static final double A = 1e-6, B = 1.0 - 1e-6;

    static int solve(double d, double a, double b, double c, double[] pts, int off, int seed) {
        if (d == 0.0) {
            int n = FinalHelpers.quadraticRoots(a, b, c, pts, off);
            return FinalHelpers.filterOutNotInAB(pts, off, n, A, B) - off;
        }
        double[] cand = new double[6];
        int nc = 0;
        if (seed <= 1) nc = FinalHelpers.cubicRootsRaw(d, a, b, c, cand, 0);
        if (seed == 0) {
            int nr = FinalHelpers.cubicRootsRaw(c, b, a, d, cand, 3);
            for (int j = 3; j < 3 + nr; j++) { double s = cand[j]; cand[j] = (s != 0.0) ? 1.0 / s : Double.NaN; }
            nc = 3 + nr;
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
                double guess = Double.NaN;
                if (seed == 3) {
                    // false position from the endpoint values already in hand: free
                    guess = lo - flo * (hi - lo) / (fhi - flo);
                } else {
                    for (int j = 0; j < nc; j++) if (cand[j] > lo && cand[j] < hi) { guess = cand[j]; break; }
                }
                double t = FinalHelpers.solveBracket(d, a, b, c, lo, hi, flo, guess);
                if (t >= A && t < B && num < 3) pts[off + num++] = t;
            } else if (fhi == 0.0 && hi < B && num < 3) pts[off + num++] = hi;
            flo = fhi;
        }
        for (int i = 0; i < ncp && num < 3; i++) {
            double tc = cp[i];
            if (tc < A || tc >= B || FinalHelpers.compHorner(d, a, b, c, tc) != 0.0) continue;
            boolean dup = false;
            for (int j = 0; j < num; j++) if (pts[off + j] == tc) dup = true;
            if (!dup) pts[off + num++] = tc;
        }
        return num;
    }

    public static void main(String[] args) throws Exception {
        // ---- timing on Marlin pixel coordinates
        int N = 1_000_000;
        java.util.Random r = new java.util.Random(7L);
        double[] cs = new double[4 * N];
        for (int i = 0; i < N; i++) {
            double x1 = r.nextDouble()*4096, x2 = r.nextDouble()*4096, x3 = r.nextDouble()*4096, x4 = r.nextDouble()*4096;
            cs[4*i] = 3.0*(x2-x3)+x4-x1; cs[4*i+1] = 3.0*(x1-2.0*x2+x3); cs[4*i+2] = 3.0*(x2-x1); cs[4*i+3] = x1 - r.nextDouble()*4096;
        }
        double[] pts = new double[8];
        for (int pass = 0; pass < 4; pass++) {
            long[] ns = new long[6];
            for (int v = 0; v <= 3; v++) {
                long t0 = System.nanoTime(); int sink = 0;
                for (int i = 0; i < N; i++) sink += solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0, v);
                ns[v] = (System.nanoTime() - t0) / N;
            }
            long t0 = System.nanoTime(); int sink = 0;
            for (int i = 0; i < N; i++) sink += EFTSolve.solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0, A, B, 4);
            ns[4] = (System.nanoTime() - t0) / N;
            long t4 = System.nanoTime(); int sink4 = 0;
            for (int i = 0; i < N; i++) sink4 += RootsUlpEval3.cubicImpl(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0, A, B, 0);
            ns[5] = (System.nanoTime() - t4) / N;
            if (pass == 3) System.out.printf("ns/solve  fwd+rev=%d  fwd=%d  midpoint=%d  false-position=%d | fma closed form (prev commit)=%d | ORIGINAL Marlin=%d%n",
                    ns[0], ns[1], ns[2], ns[3], ns[4], ns[5]);
        }

        // ---- correctness of each seeding on the hard shapes
        System.out.printf("%n%-34s %-10s %8s %9s %10s%n", "shape", "seeding", "lost", "spurious", "<=0.5ulp");
        int M = 12000;
        for (int sh : new int[]{ 0, 5, 7 }) {
            for (int v = 0; v <= 3; v++) {
                BracketSolve.rnd = new Random(777333L);
                long lost = 0, spur = 0, ok = 0, tot = 0;
                double[] got = new double[8];
                for (int i = 0; i < M; i++) {
                    double[] co = BracketSolve.shape(sh);
                    double d = co[0], a = co[1], b = co[2], c = co[3];
                    if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                    List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                    if (refs == null) continue;
                    int k = solve(d, a, b, c, got, 0, v);
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
                    for (int j = 0; j < k; j++) {
                        if (used[j]) continue;
                        boolean near = false;
                        for (BigDecimal e : refs) if (Math.abs(got[j] - e.doubleValue()) <= 1e-6) near = true;
                        if (!near) spur++;
                    }
                }
                String nm = sh == 0 ? "40 decades" : sh == 5 ? "perpendiculardfddf 1e30" : "close pair 1e-6";
                String sn = v == 0 ? "fwd+rev" : v == 1 ? "fwd" : v == 2 ? "midpoint" : "false-pos";
                System.out.printf("%-34s %-10s %8d %9d %9.2f%%%n", v == 0 ? nm : "", sn, lost, spur, tot == 0 ? Double.NaN : 100.0 * ok / tot);
            }
        }
    }
}
