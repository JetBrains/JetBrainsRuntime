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
/** Pixel error of the SHIPPED solver at device scale, against 1/512 px. */
public class PxFinal {
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 25000;
        double A = 1e-4, B = 1.0 - 1e-4, tol = 1.0/512.0;
        for (double span : new double[]{ 4096.0, 32768.0 }) {
            Random r = new Random(19283746L);
            ArrayList<Double> errs = new ArrayList<>();
            long missed = 0, roots = 0;
            double[] got = new double[8];
            for (int i = 0; i < N; i++) {
                double[] X = new double[4], Y = new double[4];
                for (int j = 0; j < 4; j++) { X[j] = r.nextDouble()*span; Y[j] = r.nextDouble()*span; }
                double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
                double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
                double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
                double pa = 2.0*(dax*dax+day*day), pb = 3.0*(dax*dbx+day*dby);
                double pc = 2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, pd = dbx*cx+dby*cy;
                if (pa == 0.0 || !Double.isFinite(pa+pb+pc+pd)) continue;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(pa, pb, pc, pd);
                if (refs == null) continue;
                int k = FinalHelpers.cubicRootsInAB(pa, pb, pc, pd, got, 0, A, B);
                double[] cu = { X[0],X[1],X[2],X[3],Y[0],Y[1],Y[2],Y[3] };
                boolean[] used = new boolean[Math.max(k,1)];
                for (BigDecimal e : refs) {
                    double ts = e.doubleValue();
                    if (ts < A || ts >= B) continue;
                    roots++;
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ts) < bd) { bd = Math.abs(got[j]-ts); best = j; }
                    if (best < 0 || bd > 0.05) { missed++; continue; }
                    used[best] = true;
                    errs.add(PixelCap.posErr(cu, got[best], ts));
                }
            }
            Collections.sort(errs);
            System.out.printf("span %-8.0f roots=%d missed=%d | median %.3e  p99.99 %.3e  max %.3e px | 1/512=%.3e  %s%n",
                    span, roots, missed, errs.get(errs.size()/2),
                    errs.get(Math.min(errs.size()-1,(int)(errs.size()*0.9999))), errs.get(errs.size()-1), tol,
                    (missed == 0 && errs.get(errs.size()-1) <= tol) ? "OK" : "FAIL");
        }
    }
}
