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
public class StopCost {
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 15000;
        StopRule.A = 1e-4; StopRule.B = 1.0 - 1e-4;
        StopRule.rnd = new Random(8080811L);
        ArrayList<double[]> cubics = new ArrayList<>();
        while (cubics.size() < N) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = StopRule.uni(0, 4096); Y[j] = StopRule.uni(0, 4096); }
            double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
            double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
            double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
            double pa = 2.0*(dax*dax+day*day), pb = 3.0*(dax*dbx+day*dby);
            double pc = 2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, pd = dbx*cx+dby*cy;
            if (pa == 0.0 || !Double.isFinite(pa+pb+pc+pd)) continue;
            cubics.add(new double[]{pa,pb,pc,pd});
        }
        String[] nm = { "none (cap 90 only)", "residual floor", "-", "floor + bracket<=1e-9", "floor + bracket<=1e-12" };
        double[] pts = new double[8];
        System.out.printf("%-26s %12s %12s%n", "stop rule", "iters/root", "ns/solve");
        for (int rule : new int[]{ 0, 1, 3, 4 }) {
            StopRule.RULE = rule;
            long bestNs = Long.MAX_VALUE; double ipr = 0;
            for (int pass = 0; pass < 3; pass++) {
                StopRule.iters = 0; StopRule.roots = 0;
                long t0 = System.nanoTime(); int sink = 0;
                for (int r = 0; r < 40; r++) for (double[] cb : cubics) sink += StopRule.solve(cb[0], cb[1], cb[2], cb[3], pts, 0);
                long ns = (System.nanoTime()-t0)/40/cubics.size();
                if (ns < bestNs) bestNs = ns;
                ipr = StopRule.iters / (double) Math.max(1, StopRule.roots);
            }
            System.out.printf("%-26s %12.2f %12d%n", nm[rule], ipr, bestNs);
        }
    }
}
