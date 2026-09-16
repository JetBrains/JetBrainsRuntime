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
 * Best iteration cap as a function of coefficient decade range and the precision
 * actually required of the roots, judged against the original (faulty) solver.
 *
 * decades  : coefficients drawn as +/- 10^U(-D/2, D/2), so D decades of spread
 * precision: a true root in [A,B) counts as found if some returned value is within
 *            this absolute distance in t. 1e-16 is below ulp(1), so it means
 *            "correctly rounded"; 1e-7 is Marlin's stated coordinate precision;
 *            1e-3 is a coarse subdivision tolerance.
 * baseline : the original Marlin closed form (RootsUlpEval3.cubicImpl mode 0).
 *
 * For every (decades, precision) it reports the smallest cap that loses nothing, and
 * the smallest that is no worse than the original, with the cost of each.
 */
public class CapMatrix {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static final int[] CAPS = { 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 64, 90 };
    static final double[] TOL = { 1e-3, 1e-7, 1e-16 };
    static final int[] DECADES = { 3, 10, 15, 30, 50, -6, -5 };   // negative = BracketSolve shape id

    static Random rnd;
    static double mag(int d) { double v = Math.pow(10.0, (rnd.nextDouble() - 0.5) * d); return rnd.nextBoolean() ? v : -v; }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 8000;
        int REP = args.length > 1 ? Integer.parseInt(args[1]) : 40;

        System.out.printf("N=%d polynomials per decade range, timing over %d repetitions%n", N, N * REP);
        System.out.printf("%n%-8s %-10s | %-22s | %-24s | %-24s%n", "decades", "precision",
                "original (faulty)", "smallest cap, no loss", "smallest cap <= original");
        System.out.printf("%-8s %-10s | %8s %12s | %5s %8s %8s | %5s %8s %8s%n", "", "",
                "lost", "ns/solve", "cap", "ns", "lost", "cap", "ns", "lost");

        for (int D : DECADES) {
            // ---- build the sample and its exact roots once
            rnd = new Random(99001L + D);
            BracketSolve.rnd = new Random(99001L + D);
            ArrayList<double[]> polys = new ArrayList<>();
            ArrayList<double[]> roots = new ArrayList<>();
            while (polys.size() < N) {
                double d, a, b, c;
                if (D < 0) { double[] co = BracketSolve.shape(-D); d = co[0]; a = co[1]; b = co[2]; c = co[3]; }
                else { d = mag(D); a = mag(D); b = mag(D); c = mag(D); }
                if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) continue;
                ArrayList<Double> in = new ArrayList<>();
                for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) in.add(ed); }
                polys.add(new double[]{ d, a, b, c });
                double[] rr = new double[in.size()];
                for (int i = 0; i < rr.length; i++) rr[i] = in.get(i);
                roots.add(rr);
            }
            long trueRoots = 0;
            for (double[] rr : roots) trueRoots += rr.length;

            // ---- losses per cap per tolerance, and for the original
            long[][] lost = new long[CAPS.length][TOL.length];
            long[] lostOrig = new long[TOL.length];
            double[] got = new double[8];
            for (int ci = 0; ci < CAPS.length; ci++) {
                CapSweep.CAP = CAPS[ci];
                for (int p = 0; p < polys.size(); p++) {
                    double[] co = polys.get(p);
                    int k = CapSweep.solve(co[0], co[1], co[2], co[3], got, 0);
                    count(got, k, roots.get(p), lost[ci]);
                }
            }
            for (int p = 0; p < polys.size(); p++) {
                double[] co = polys.get(p);
                int k = RootsUlpEval3.cubicImpl(co[0], co[1], co[2], co[3], got, 0, A, B, 0);
                count(got, k, roots.get(p), lostOrig);
            }

            // ---- cost per cap, and of the original
            long[] ns = new long[CAPS.length];
            for (int ci = 0; ci < CAPS.length; ci++) {
                CapSweep.CAP = CAPS[ci];
                ns[ci] = time(polys, REP, false);
            }
            long nsOrig = time(polys, REP, true);

            for (int ti = 0; ti < TOL.length; ti++) {
                int capZero = -1, capPar = -1;
                for (int ci = 0; ci < CAPS.length; ci++) {
                    if (capZero < 0 && lost[ci][ti] == 0) capZero = ci;
                    if (capPar < 0 && lost[ci][ti] <= lostOrig[ti]) capPar = ci;
                }
                System.out.printf("%-8s %-10s | %8s %12d | %5s %8s %8s | %5s %8s %8s%n",
                        name(D), fmt(TOL[ti]),
                        lostOrig[ti] + "/" + trueRoots, nsOrig,
                        capZero < 0 ? "none" : String.valueOf(CAPS[capZero]),
                        capZero < 0 ? "-" : String.valueOf(ns[capZero]),
                        capZero < 0 ? "-" : String.valueOf(lost[capZero][ti]),
                        capPar < 0 ? "none" : String.valueOf(CAPS[capPar]),
                        capPar < 0 ? "-" : String.valueOf(ns[capPar]),
                        capPar < 0 ? "-" : String.valueOf(lost[capPar][ti]));
            }
            // full loss curve, useful when the two columns above coincide
            System.out.print("         loss by cap (tol 1e-16): ");
            for (int ci = 0; ci < CAPS.length; ci++) System.out.printf("%d:%d ", CAPS[ci], lost[ci][2]);
            System.out.printf("| ns: ");
            for (int ci = 0; ci < CAPS.length; ci++) System.out.printf("%d:%d ", CAPS[ci], ns[ci]);
            System.out.println();
        }
    }

    static void count(double[] got, int k, double[] refs, long[] lost) {
        for (int ti = 0; ti < TOL.length; ti++) {
            boolean[] used = new boolean[Math.max(k, 1)];
            for (double ed : refs) {
                int best = -1; double bd = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j] - ed) < bd) { bd = Math.abs(got[j] - ed); best = j; }
                if (best < 0 || bd > TOL[ti]) lost[ti]++; else used[best] = true;
            }
        }
    }

    static long time(List<double[]> polys, int rep, boolean orig) {
        double[] pts = new double[8];
        long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int sink = 0;
            for (int r = 0; r < rep; r++)
                for (double[] co : polys)
                    sink += orig ? RootsUlpEval3.cubicImpl(co[0], co[1], co[2], co[3], pts, 0, A, B, 0)
                                 : CapSweep.solve(co[0], co[1], co[2], co[3], pts, 0);
            long ns = (System.nanoTime() - t0) / (long) rep / polys.size();
            if (ns < best) best = ns;
        }
        return best;
    }

    static String name(int D) { return D == -6 ? "px xPts" : D == -5 ? "perp1e30" : String.valueOf(D); }

    static String fmt(double t) { return t == 1e-3 ? "1e-3" : t == 1e-7 ? "1e-7" : "1e-16"; }
}
