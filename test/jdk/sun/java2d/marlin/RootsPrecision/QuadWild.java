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
/** Quadratic robustness: NaN/Inf and lost/spurious roots vs exact, over 20-decade coefficients. */
public class QuadWild {
    static Random rnd; static final double A = 1e-6, B = 1.0 - 1e-6, TOL = 1e-6;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double sc(int h) { double s = Math.pow(10.0, uni(-h, h)); return rnd.nextBoolean() ? s : -s; }
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 200000;
        int half = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        String[] names = { "Kahan only (previous)", "+ compensated-Horner Newton" };
        for (int mode : new int[]{ 0, 2 }) {
            rnd = new Random(8080L);
            double[] z = new double[4];
            long nan = 0, lost = 0, spurious = 0, trueRoots = 0, polys = 0;
            for (int i = 0; i < N; i++) {
                double a, b, c;
                if (i % 3 == 0) { a = sc(half); b = sc(half); c = sc(half); }
                else if (i % 3 == 1) { double r = uni(A, B), s = uni(A, B); a = sc(half); b = -a * (r + s); c = a * r * s; }
                else { double r = uni(A, B); a = sc(half); b = -2 * a * r; c = a * r * r; }   // double root
                int k = QuadNewton.solve(a, b, c, z, 0, mode);
                k = RootsUlpEval2.filterOutNotInAB(z, 0, k, A, B);
                for (int j = 0; j < k; j++) if (!Double.isFinite(z[j])) nan++;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(0.0, a, b, c);
                if (refs == null) continue;
                polys++;
                boolean[] used = new boolean[Math.max(k, 1)];
                for (BigDecimal e : refs) {
                    double ed = e.doubleValue();
                    if (ed < A || ed >= B) continue;
                    trueRoots++;
                    int best = -1; double bd = Double.MAX_VALUE;
                    for (int j = 0; j < k; j++) if (!used[j] && Math.abs(z[j] - ed) < bd) { bd = Math.abs(z[j] - ed); best = j; }
                    if (best < 0 || bd > TOL) lost++; else used[best] = true;
                }
                for (int j = 0; j < k; j++) {
                    if (used[j]) continue;
                    boolean near = false;
                    for (BigDecimal e : refs) if (Math.abs(z[j] - e.doubleValue()) <= TOL) near = true;
                    if (!near) spurious++;
                }
            }
            System.out.printf("%-30s polys=%7d trueRoots=%7d | NaN/Inf=%d lost=%d spurious=%d%n",
                    names[mode == 0 ? 0 : 1], polys, trueRoots, nan, lost, spurious);
        }
    }
}
