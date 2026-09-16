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
/** For polys where the patch returns FEWER roots, how far apart are the true roots that got merged? */
public class GapScan {
    static Random rnd = new Random(12345L);
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double scale() { double s = Math.pow(10.0, uni(-6, 6)); return rnd.nextBoolean() ? s : -s; }
    public static void main(String[] args) {
        double A = 1e-6, B = 1.0 - 1e-6;
        double[] r0 = new double[4], r2 = new double[4];
        ArrayList<Double> gaps = new ArrayList<>();
        long fewer = 0, unknownRef = 0, wellSeparated = 0;
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 400000;
        for (int i = 0; i < N; i++) {
            double d, p, q, c;
            switch (i % 5) {
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
            fewer++;
            List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, p, q, c);
            if (refs == null || refs.size() < 2) { unknownRef++; continue; }
            // smallest relative gap between adjacent true roots
            double best = Double.MAX_VALUE;
            for (int j = 0; j + 1 < refs.size(); j++) {
                double lo = refs.get(j).doubleValue(), hi = refs.get(j + 1).doubleValue();
                double rel = Math.abs(hi - lo) / Math.max(1e-300, Math.max(Math.abs(lo), Math.abs(hi)));
                best = Math.min(best, rel);
            }
            gaps.add(best);
            if (best > 1e-5) wellSeparated++;
        }
        Collections.sort(gaps);
        System.out.printf("polys where patch returns fewer roots: %d (reference uncertain/single: %d)%n", fewer, unknownRef);
        if (!gaps.isEmpty()) {
            System.out.printf("relative gap between the merged true roots: min=%.3g p10=%.3g median=%.3g p90=%.3g max=%.3g%n",
                    gaps.get(0), gaps.get(gaps.size()/10), gaps.get(gaps.size()/2), gaps.get(gaps.size()*9/10), gaps.get(gaps.size()-1));
            System.out.printf("of those, gap > 1e-5 (genuinely separated roots merged): %d  (%.4f%% of measured)%n",
                    wellSeparated, 100.0 * wellSeparated / gaps.size());
        }
    }
}
