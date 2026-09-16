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
public class FinalRobust {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static Random rnd = new Random(5150L);
    static double pick() {
        switch (rnd.nextInt(8)) {
            case 0: return 0.0;
            case 1: return rnd.nextBoolean() ? Double.MIN_NORMAL : -Double.MIN_NORMAL;
            case 2: return rnd.nextBoolean() ? 1e-300 : -1e-300;
            case 3: return rnd.nextBoolean() ? 1e300 : -1e300;
            case 4: return rnd.nextBoolean() ? 1e-15 : -1e-15;
            default: { double v = Math.pow(10.0, -20 + 40 * rnd.nextDouble()); return rnd.nextBoolean() ? v : -v; }
        }
    }
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 3000000;
        double[] pts = new double[8];
        long bad = 0, nonRoot = 0, total = 0, exc = 0;
        for (int i = 0; i < N; i++) {
            double d, a, b, c;
            if (i % 4 == 0) { double r = A + (B - A) * rnd.nextDouble(); d = pick();
                              a = -2 * d * r; b = d * r * r; c = 0.0; }        // double root at r
            else if (i % 4 == 1) { double r = A + (B - A) * rnd.nextDouble(); d = pick();
                              a = -3 * d * r; b = 3 * d * r * r; c = -d * r * r * r; }  // triple root
            else { d = pick(); a = pick(); b = pick(); c = pick(); }
            int k;
            try { k = FinalHelpers.cubicRootsInAB(d, a, b, c, pts, 0, A, B); }
            catch (Throwable t) { exc++; continue; }
            total += k;
            for (int j = 0; j < k; j++) {
                double t = pts[j];
                if (!Double.isFinite(t) || t < A || t >= B) { bad++; continue; }
                double t2 = t * t;
                double scale = Math.max(Math.max(Math.abs(d * t2 * t), Math.abs(a * t2)), Math.max(Math.abs(b * t), Math.abs(c)));
                double f = Math.abs(FinalHelpers.compHorner(d, a, b, c, t));
                if (scale > 0.0 && f > 65536.0 * Math.ulp(scale)) nonRoot++;
            }
        }
        System.out.printf("%d polynomials (zeros, subnormals, 1e+-300, 1e-15, 40 decades, exact double and triple roots)%n", N);
        System.out.printf("  roots returned=%d | out of range or non-finite=%d | failing residual=%d | exceptions=%d%n", total, bad, nonRoot, exc);
    }
}
