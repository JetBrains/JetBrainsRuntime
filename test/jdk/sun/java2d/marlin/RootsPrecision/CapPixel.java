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
/** Cap needed by the cubic that findSubdivPoints actually solves, at pixel scale. */
public class CapPixel {
    static final double A = 1e-4, B = 1.0 - 1e-4;     // Helpers' T_A / T_B, the real bounds here
    static final int[] CAPS = { 4, 6, 8, 10, 12, 14, 16, 20, 24, 32, 40, 90 };
    static final double[] TOL = { 1e-3, 1e-7, 1e-8, 1e-9, 1e-16 };
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }

    static double[] perpPixel(double span) {
        double[] X = new double[4], Y = new double[4];
        for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
        double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
        double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
        double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
        return new double[]{ 2.0*(dax*dax+day*day), 3.0*(dax*dbx+day*dby),
                             2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, dbx*cx+dby*cy };
    }
    static double[] xPtsPixel(double span) {
        double x1 = uni(0,span), x2 = uni(0,span), x3 = uni(0,span), x4 = uni(0,span);
        double lo = Math.min(Math.min(x1,x2),Math.min(x3,x4)), hi = Math.max(Math.max(x1,x2),Math.max(x3,x4));
        return new double[]{ 3.0*(x2-x3)+x4-x1, 3.0*(x1-2.0*x2+x3), 3.0*(x2-x1), x1 - uni(lo,hi) };
    }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 12000;
        for (double span : new double[]{ 4096.0, 1.0e6 }) {
            for (int which = 0; which < 2; which++) {
                rnd = new Random(606061L);
                ArrayList<double[]> polys = new ArrayList<>();
                ArrayList<double[]> roots = new ArrayList<>();
                while (polys.size() < N) {
                    double[] co = (which == 0) ? perpPixel(span) : xPtsPixel(span);
                    if (co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3])) continue;
                    List<BigDecimal> refs = RootsUlpEval2.referenceRoots(co[0], co[1], co[2], co[3]);
                    if (refs == null) continue;
                    ArrayList<Double> in = new ArrayList<>();
                    for (BigDecimal e : refs) { double ed = e.doubleValue(); if (ed >= A && ed < B) in.add(ed); }
                    polys.add(co);
                    double[] rr = new double[in.size()];
                    for (int i = 0; i < rr.length; i++) rr[i] = in.get(i);
                    roots.add(rr);
                }
                long trueRoots = 0; for (double[] rr : roots) trueRoots += rr.length;
                long[][] lost = new long[CAPS.length][TOL.length];
                double[] got = new double[8];
                CapSweep.A = A; CapSweep.B = B;
                for (int ci = 0; ci < CAPS.length; ci++) {
                    CapSweep.CAP = CAPS[ci];
                    for (int p = 0; p < polys.size(); p++) {
                        double[] co = polys.get(p);
                        int k = CapSweep.solve(co[0], co[1], co[2], co[3], got, 0);
                        for (int ti = 0; ti < TOL.length; ti++) {
                            boolean[] used = new boolean[Math.max(k, 1)];
                            for (double ed : roots.get(p)) {
                                int best = -1; double bd = Double.MAX_VALUE;
                                for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                                if (best < 0 || bd > TOL[ti]) lost[ci][ti]++; else used[best] = true;
                            }
                        }
                    }
                }
                long[] lostOrig = new long[TOL.length];
                for (int p = 0; p < polys.size(); p++) {
                    double[] co = polys.get(p);
                    int k = RootsUlpEval3.cubicImpl(co[0], co[1], co[2], co[3], got, 0, A, B, 0);
                    for (int ti = 0; ti < TOL.length; ti++) {
                        boolean[] used = new boolean[Math.max(k, 1)];
                        for (double ed : roots.get(p)) {
                            int best = -1; double bd = Double.MAX_VALUE;
                            for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                            if (best < 0 || bd > TOL[ti]) lostOrig[ti]++; else used[best] = true;
                        }
                    }
                }
                CapSweep.A = A; CapSweep.B = B;
                long[] ns = new long[CAPS.length];
                for (int ci = 0; ci < CAPS.length; ci++) { CapSweep.CAP = CAPS[ci]; ns[ci] = time(polys, 40, false); }
                long nsOrig = time(polys, 40, true);

                System.out.printf("%n#### %s, coords 0..%.0f : %d roots in [%.0e, 1-%.0e)  (original %d ns)%n",
                        which == 0 ? "perpendiculardfddf" : "xPoints", span, trueRoots, A, A, nsOrig);
                System.out.printf("%-10s %14s | %8s %8s %8s%n", "precision", "original lost", "cap", "ns", "ratio");
                for (int ti = 0; ti < TOL.length; ti++) {
                    int cz = -1;
                    for (int ci = 0; ci < CAPS.length; ci++) if (lost[ci][ti] == 0) { cz = ci; break; }
                    System.out.printf("%-10s %14s | %8s %8s %8s%n", fmt(TOL[ti]), lostOrig[ti] + "/" + trueRoots,
                            cz < 0 ? "none" : String.valueOf(CAPS[cz]), cz < 0 ? "-" : String.valueOf(ns[cz]),
                            cz < 0 ? "-" : String.format("%.1fx", ns[cz] / (double) nsOrig));
                }
                System.out.print("           loss by cap at 1e-8: ");
                for (int ci = 0; ci < CAPS.length; ci++) System.out.printf("%d:%d ", CAPS[ci], lost[ci][2]);
                System.out.println();
            }
        }
    }
    static long time(List<double[]> polys, int rep, boolean orig) {
        double[] pts = new double[8]; long best = Long.MAX_VALUE;
        for (int pass = 0; pass < 3; pass++) {
            long t0 = System.nanoTime(); int sink = 0;
            for (int r = 0; r < rep; r++) for (double[] co : polys)
                sink += orig ? RootsUlpEval3.cubicImpl(co[0], co[1], co[2], co[3], pts, 0, A, B, 0)
                             : CapSweep.solve(co[0], co[1], co[2], co[3], pts, 0);
            long ns = (System.nanoTime() - t0) / rep / polys.size();
            if (ns < best) best = ns;
        }
        return best;
    }
    static String fmt(double t) { return String.format("%.0e", t); }
}
