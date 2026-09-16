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

import java.util.*;
public class CostFinal {
    public static void main(String[] args) {
        int N = 400000;
        Random r = new Random(4242L);
        double A = 1e-4, B = 1.0 - 1e-4;
        ArrayList<double[]> cs = new ArrayList<>();
        while (cs.size() < N) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = r.nextDouble()*4096; Y[j] = r.nextDouble()*4096; }
            double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
            double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
            double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
            double pa = 2.0*(dax*dax+day*day), pb = 3.0*(dax*dbx+day*dby);
            double pc = 2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, pd = dbx*cx+dby*cy;
            if (pa == 0.0 || !Double.isFinite(pa+pb+pc+pd)) continue;
            cs.add(new double[]{pa,pb,pc,pd});
        }
        double[] pts = new double[8];
        long shipped = 0, orig = 0;
        for (int pass = 0; pass < 4; pass++) {
            long t0 = System.nanoTime(); int s1 = 0;
            for (double[] cb : cs) s1 += FinalHelpers.cubicRootsInAB(cb[0], cb[1], cb[2], cb[3], pts, 0, A, B);
            long t1 = System.nanoTime(); int s2 = 0;
            for (double[] cb : cs) s2 += RootsUlpEval3.cubicImpl(cb[0], cb[1], cb[2], cb[3], pts, 0, A, B, 0);
            long t2 = System.nanoTime();
            shipped = (t1-t0)/cs.size(); orig = (t2-t1)/cs.size();
        }
        System.out.printf("perpendiculardfddf, device-scale curves: shipped %d ns/solve | original closed form %d ns/solve | %.1fx%n",
                shipped, orig, shipped / (double) orig);
    }
}
