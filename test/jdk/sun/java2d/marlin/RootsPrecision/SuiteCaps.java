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
public class SuiteCaps {
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        double A = 1e-6, B = 1.0 - 1e-6;
        CapSweep.A = A; CapSweep.B = B;
        String[] shapes = { "40 decades", "30 decades", "20 decades", "tiny 1e-15..1e-5",
                            "3 roots d 40dec", "perp 1e-7..1e30", "xPoints px", "close pair 1e-6" };
        int[] caps = { 32, 40, 48, 64, 90 };
        long[][] lost = new long[shapes.length][caps.length];
        double[] got = new double[8];
        for (int si = 0; si < shapes.length; si++) {
            // build sample once
            BracketSolve.rnd = new Random(777333L);
            ArrayList<double[]> ps = new ArrayList<>(), rs = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                double[] co = BracketSolve.shape(si);
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
            for (int ci = 0; ci < caps.length; ci++) {
                CapSweep.CAP = caps[ci];
                long l = 0;
                for (int p = 0; p < ps.size(); p++) {
                    double[] co = ps.get(p);
                    int k = CapSweep.solve(co[0], co[1], co[2], co[3], got, 0);
                    boolean[] used = new boolean[Math.max(k, 1)];
                    for (double ed : rs.get(p)) {
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                        if (best < 0 || bd > 1e-6) l++; else used[best] = true;
                    }
                }
                lost[si][ci] = l;
            }
            System.out.printf("%-20s", shapes[si]);
            for (int ci = 0; ci < caps.length; ci++) System.out.printf(" | cap%-3d %5d", caps[ci], lost[si][ci]);
            System.out.println();
        }
        System.out.printf("%-20s", "TOTAL lost");
        for (int ci = 0; ci < caps.length; ci++) {
            long t = 0; for (int si = 0; si < shapes.length; si++) t += lost[si][ci];
            System.out.printf(" | cap%-3d %5d", caps[ci], t);
        }
        System.out.println();
    }
}
