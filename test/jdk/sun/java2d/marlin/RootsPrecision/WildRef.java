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
 * Wild-coefficient stress test scored against the exact reference, not against the current code.
 * lost     = a true root in [A,B) with no returned value within 1e-6 of it
 * spurious = a returned value with no true root within 1e-6 of it
 */
public class WildRef {
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double scale() { double s = Math.pow(10.0, uni(-6, 6)); return rnd.nextBoolean() ? s : -s; }
    static final double A = 1e-6, B = 1.0 - 1e-6, TOL = 1e-6;

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 200000;
        System.out.printf("%-26s %10s %10s %10s %10s %12s%n", "variant", "polys", "trueRoots", "lost", "spurious", "worstLostGap");
        for (int mode = 0; mode <= 3; mode++) {
            rnd = new Random(777L);
            double[] r = new double[4];
            long polys = 0, trueRoots = 0, lost = 0, spurious = 0, uncertain = 0;
            double worst = 0; String worstCase = "";
            for (int i = 0; i < N; i++) {
                double d = scale(), a = scale(), b = scale(), c = scale();
                int k = (mode == 0) ? NanScan.Orig.cubic(d, a, b, c, r, 0, A, B)
                                    : RootsUlpEval3.cubicImpl(d, a, b, c, r, 0, A, B, mode);
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                if (refs == null) { uncertain++; continue; }
                polys++;
                boolean[] used = new boolean[Math.max(k, 1)];
                for (BigDecimal e : refs) {
                    double ed = e.doubleValue();
                    if (ed < A || ed >= B) continue;
                    trueRoots++;
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(r[j] - ed) < bd) { bd = Math.abs(r[j] - ed); best = j; }
                    if (best < 0 || bd > TOL) {
                        lost++;
                        if (bd > worst && bd < Double.MAX_VALUE) { worst = bd; }
                        if (best < 0) worst = Math.max(worst, 1.0);
                    } else used[best] = true;
                }
                for (int j = 0; j < k; j++) {
                    if (used[j]) continue;
                    boolean nearAny = false;
                    for (BigDecimal e : refs) if (Math.abs(r[j] - e.doubleValue()) <= TOL) nearAny = true;
                    if (!nearAny) spurious++;
                }
            }
            String name = switch (mode) { case 0 -> "current Helpers"; case 1 -> "fma"; case 2 -> "fma + relative tol"; default -> "fma + sign of D"; };
            System.out.printf("%-26s %10d %10d %10d %10d %12.3g%n", name, polys, trueRoots, lost, spurious, worst);
        }
    }
}
