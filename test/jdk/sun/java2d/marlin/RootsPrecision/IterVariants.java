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
 * The ranges are already the monotone pieces between the roots of f' (at most 3).
 * Open question: within a range, is false position as the ITERATION cheaper than
 * Newton? Newton needs f' every step (2 fma); false position needs none, but
 * converges superlinearly rather than quadratically. Cost per step is nearly equal,
 * so the iteration count decides.
 *   0 = Newton + bisection safeguard, false-position seed   (shipped)
 *   1 = pure regula falsi + bisection safeguard
 *   2 = Illinois variant (halve the retained endpoint's value)
 */
public class IterVariants {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static long ranges = 0, solves = 0, iters = 0, rootsFound = 0;

    /** mode 5: Newton, but the bisection fallback is GEOMETRIC while the bracket spans
     *  more than a factor of two. [A,B) is inside (0,1) so lo > 0 always holds. */
    static long[] hist5 = new long[95];
    static double refine5(double d, double a, double b, double c,
                          double lo, double hi, double flo, double fhi) {
        double t = lo - flo * (hi - lo) / (fhi - flo);
        if (!(t > lo && t < hi)) t = Math.sqrt(lo * hi);
        double best = t, fbest = Double.POSITIVE_INFINITY;
        boolean negLo = flo < 0.0;
        int it = 0;
        for (; it < 90; it++) {
            iters++;
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            if (f == 0.0) { hist5[it]++; return t; }
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            if ((f < 0.0) == negLo) lo = t; else hi = t;
            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                // geometric while the bracket spans decades, arithmetic once it is tight
                nt = (hi > 2.0 * lo) ? Math.sqrt(lo * hi) : 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) {
                    nt = 0.5 * (lo + hi);
                    if (nt <= lo || nt >= hi) break;
                }
            }
            if (nt == t) break;
            t = nt;
        }
        hist5[Math.min(it, 94)]++;
        return best;
    }

    /** mode 4: shipped scheme plus a step-size convergence exit */
    static long[] hist = new long[95];
    static double refine4(double d, double a, double b, double c,
                          double lo, double hi, double flo, double fhi) {
        double t = lo - flo * (hi - lo) / (fhi - flo);
        if (!(t > lo && t < hi)) t = 0.5 * (lo + hi);
        double best = t, fbest = Double.POSITIVE_INFINITY;
        boolean negLo = flo < 0.0;
        int it = 0;
        for (; it < 90; it++) {
            iters++;
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            if (f == 0.0) { hist[it]++; return t; }
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            if ((f < 0.0) == negLo) lo = t; else hi = t;
            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                nt = 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) break;
            }
            // the step is below the representable resolution: t is the root
            if (Math.abs(nt - t) <= Math.ulp(t)) {
                double fn = FinalHelpers.compHorner(d, a, b, c, nt);
                iters++;
                if (Math.abs(fn) < fbest) { best = nt; }
                break;
            }
            if (nt == t) break;
            t = nt;
        }
        hist[Math.min(it, 94)]++;
        return best;
    }

    /** mode 3: Newton, falling back to FALSE POSITION (not the midpoint) inside the bracket */
    static double refine3(double d, double a, double b, double c,
                          double lo, double hi, double flo, double fhi) {
        double t = lo - flo * (hi - lo) / (fhi - flo);
        if (!(t > lo && t < hi)) t = 0.5 * (lo + hi);
        double best = t, fbest = Double.POSITIVE_INFINITY;
        boolean negLo = flo < 0.0;
        for (int it = 0; it < 90; it++) {
            iters++;
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            if (f == 0.0) return t;
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            if ((f < 0.0) == negLo) { lo = t; flo = f; } else { hi = t; fhi = f; }
            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                nt = lo - flo * (hi - lo) / (fhi - flo);          // secant inside the bracket
                if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                    nt = 0.5 * (lo + hi);
                    if (nt <= lo || nt >= hi) break;
                }
            }
            if (nt == t) break;
            t = nt;
        }
        return best;
    }

    static double refine(double d, double a, double b, double c,
                         double lo, double hi, double flo, double fhi, int mode) {
        if (mode == 3) return refine3(d, a, b, c, lo, hi, flo, fhi);
        if (mode == 4) return refine4(d, a, b, c, lo, hi, flo, fhi);
        if (mode == 5) return refine5(d, a, b, c, lo, hi, flo, fhi);
        double best, fbest = Double.POSITIVE_INFINITY;
        double t = lo - flo * (hi - lo) / (fhi - flo);
        if (!(t > lo && t < hi)) t = 0.5 * (lo + hi);
        best = t;
        boolean negLo = flo < 0.0;
        for (int it = 0; it < 90; it++) {
            iters++;
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            if (f == 0.0) return t;
            double nt;
            if (mode == 0) {
                double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
                nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            } else {
                nt = Double.NaN;   // filled after the bracket update below
            }
            boolean sameAsLo = (f < 0.0) == negLo;
            if (sameAsLo) { lo = t; flo = f; if (mode == 2) fhi *= 0.5; }
            else          { hi = t; fhi = f; if (mode == 2) flo *= 0.5; }
            if (mode != 0) nt = lo - flo * (hi - lo) / (fhi - flo);
            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                nt = 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) break;
            }
            if (nt == t) break;
            t = nt;
        }
        return best;
    }

    static int solve(double d, double a, double b, double c, double[] pts, int off, int mode) {
        if (d == 0.0) {
            int n = FinalHelpers.quadraticRoots(a, b, c, pts, off);
            return FinalHelpers.filterOutNotInAB(pts, off, n, A, B) - off;
        }
        solves++;
        double[] cp = new double[2];
        int ncp = FinalHelpers.criticalPoints(d, a, b, cp);
        double[] bnd = new double[4];
        int nb = 0; bnd[nb++] = A;
        for (int i = 0; i < ncp; i++) if (cp[i] > A && cp[i] < B && cp[i] > bnd[nb-1]) bnd[nb++] = cp[i];
        bnd[nb++] = B;
        ranges += (nb - 1);
        int num = 0;
        double flo = FinalHelpers.compHorner(d, a, b, c, bnd[0]);
        if (flo == 0.0) pts[off + num++] = bnd[0];
        for (int i = 0; i + 1 < nb; i++) {
            double lo = bnd[i], hi = bnd[i+1];
            double fhi = FinalHelpers.compHorner(d, a, b, c, hi);
            if (flo != 0.0 && fhi != 0.0 && ((flo < 0.0) != (fhi < 0.0))) {
                double t = refine(d, a, b, c, lo, hi, flo, fhi, mode);
                if (t >= A && t < B && num < 3) { pts[off + num++] = t; rootsFound++; }
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
        int N = 1_000_000;
        Random r = new Random(7L);
        double[] cs = new double[4 * N];
        for (int i = 0; i < N; i++) {
            double x1 = r.nextDouble()*4096, x2 = r.nextDouble()*4096, x3 = r.nextDouble()*4096, x4 = r.nextDouble()*4096;
            cs[4*i] = 3.0*(x2-x3)+x4-x1; cs[4*i+1] = 3.0*(x1-2.0*x2+x3); cs[4*i+2] = 3.0*(x2-x1); cs[4*i+3] = x1 - r.nextDouble()*4096;
        }
        double[] pts = new double[8];
        String[] nm = { "Newton+midpoint", "regula falsi", "Illinois", "Newton+false position", "Newton+step exit", "Newton+geometric" };
        for (int pass = 0; pass < 4; pass++) {
            for (int m = 0; m < 6; m++) {
                ranges = solves = iters = rootsFound = 0;
                long t0 = System.nanoTime(); int sink = 0;
                for (int i = 0; i < N; i++) sink += solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0, m);
                long ns = (System.nanoTime() - t0) / N;
                if (pass == 3)
                    System.out.printf("%-18s %6d ns/solve | ranges/solve %.2f | iterations/root %.2f | roots %d%n",
                            nm[m], ns, ranges / (double) solves, iters / (double) Math.max(1, rootsFound), rootsFound);
            }
        }
        long tot5 = 0, sum5 = 0;
        for (int i = 0; i < 95; i++) { tot5 += hist5[i]; sum5 += (long) i * hist5[i]; }
        System.out.printf("geometric variant: mean %.1f iterations, p50 around ", tot5 == 0 ? 0.0 : sum5 / (double) tot5);
        long acc = 0; for (int i = 0; i < 95; i++) { acc += hist5[i]; if (acc * 2 >= tot5) { System.out.println(i); break; } }
        // correctness of each iteration scheme on the hard shapes
        System.out.printf("%n%-28s %-18s %8s %9s %10s%n", "shape", "iteration", "lost", "spurious", "<=0.5ulp");
        for (int sh : new int[]{ 0, 5 }) {
            for (int m = 0; m < 6; m++) {
                BracketSolve.rnd = new Random(777333L);
                long lost = 0, spur = 0, ok = 0, tot = 0;
                double[] got = new double[8];
                for (int i = 0; i < 12000; i++) {
                    double[] co = BracketSolve.shape(sh);
                    double d = co[0], a = co[1], b = co[2], c = co[3];
                    if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                    List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                    if (refs == null) continue;
                    int k = solve(d, a, b, c, got, 0, m);
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
                System.out.printf("%-28s %-18s %8d %9d %9.2f%%%n",
                        m == 0 ? (sh == 0 ? "40 decades" : "perpendiculardfddf 1e30") : "", nm[m], lost, spur,
                        tot == 0 ? Double.NaN : 100.0 * ok / tot);
            }
        }
    }
}
