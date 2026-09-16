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
public class WhyFewer {
    static Random rnd = new Random(12345L);
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double scale() { double s = Math.pow(10.0, uni(-6, 6)); return rnd.nextBoolean() ? s : -s; }
    public static void main(String[] args) {
        double A = 1e-6, B = 1.0 - 1e-6;
        double[] r0 = new double[4], r2 = new double[4];
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 400000;
        long[] byShape = new long[5];
        long printed = 0;
        for (int i = 0; i < N; i++) {
            double d, p, q, c; int shape = i % 5;
            switch (shape) {
                case 0 -> { d = scale(); p = scale(); q = scale(); c = scale(); }
                case 1 -> { double x = uni(0,1), y = uni(0,1), z = uni(0,1); d = scale();
                            p = -d*(x+y+z); q = d*(x*y+x*z+y*z); c = -d*x*y*z; }
                case 2 -> { double x = uni(0,1e-3), y = x, z = uni(0,1); d = scale();
                            p = -d*(x+y+z); q = d*(x*y+x*z+y*z); c = -d*x*y*z; }
                case 3 -> { double x = uni(0,1); d = scale();
                            p = -3*d*x; q = 3*d*x*x; c = -d*x*x*x; }
                default -> { d = 0.0; p = scale(); q = scale(); c = scale(); }
            }
            int k0 = NanScan.Orig.cubic(d, p, q, c, r0, 0, A, B);
            int k2 = RootsUlpEval3.cubicImpl(d, p, q, c, r2, 0, A, B, 2);
            if (k2 >= k0) continue;
            byShape[shape]++;
            if (shape != 4 && printed < 6) {
                printed++;
                List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, p, q, c);
                System.out.printf("shape=%d d=%s a=%s b=%s c=%s%n  current -> %s%n  patched -> %s%n  refs    -> %s%n",
                        shape, d, p, q, c,
                        Arrays.toString(Arrays.copyOf(r0, k0)), Arrays.toString(Arrays.copyOf(r2, k2)),
                        refs == null ? "uncertain" : refs.toString());
            }
        }
        System.out.printf("%nfewer-root polys by shape: wild=%d, 3roots[0,1)=%d, doubleRoot=%d, tripleRoot=%d, d==0(quadratic)=%d%n",
                byShape[0], byShape[1], byShape[2], byShape[3], byShape[4]);
    }
}
