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
/** Scores the methods extracted verbatim from the committed Helpers.java. */
public class FinalCheck {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 30000;
        String[] shapes = { "xPoints, coords 1e-7..1e30", "perpendiculardfddf, coords 1e-7..1e30",
                            "xPoints, coords 0..4096", "3 roots in [A,B)", "close pair gap 1e-6",
                            "wild coefficients, 20 decades", "wild coefficients, 30 decades" };
        int[] idx = { 0, 1, 2, 3, 4, 5, 6 };
        System.out.printf("%-40s %8s %9s %10s %8s%n", "shape (FinalHelpers, as committed)", "lost", "spurious", "<=0.5ulp", "NaN");
        for (int si = 0; si < idx.length; si++) {
            RecipSolve.rnd = new Random(20240915L);
            double[] got = new double[8];
            long lost = 0, spur = 0, ok = 0, tot = 0, nan = 0, polys = 0, trueRoots = 0;
            for (int i = 0; i < N; i++) {
                double[] co = RecipSolve.shapeCubic(idx[si]);
                double d = co[0], a = co[1], b = co[2], c = co[3];
                if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) continue;
                polys++;
                int k = FinalHelpers.cubicRootsInAB(d, a, b, c, got, 0, A, B);
                for (int j = 0; j < k; j++) if (!Double.isFinite(got[j])) nan++;
                boolean[] used = new boolean[Math.max(k, 1)];
                for (BigDecimal e : refs) {
                    double ed = e.doubleValue();
                    if (ed < A || ed >= B) continue;
                    trueRoots++;
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
            System.out.printf("%-40s %8d %9d %9.2f%% %8d   (%d polys, %d roots)%n",
                    shapes[idx[si]], lost, spur, tot == 0 ? Double.NaN : 100.0 * ok / tot, nan, polys, trueRoots);
        }
    }
}
