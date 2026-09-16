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
 * Honest geometric error: for every reference root in [A,B), the distance to the NEAREST root the solver
 * returned (no pairing cap, no exclusions). This is what a consumer of the roots actually suffers.
 * If the solver returned nothing, the root is reported as fully missed (distance = its own magnitude is
 * meaningless, so those are counted separately).
 */
public class TrueAbsErr {
    static double A = 1e-6, B = 1.0 - 1e-6;
    static Random rnd = new Random(0x5EEDL);
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double logUni(double lo, double hi) { return Math.exp(uni(Math.log(lo), Math.log(hi))); }
    static double scale() { double s = Math.pow(10.0, uni(-3, 3)); return rnd.nextBoolean() ? s : -s; }

    static void run(String name, int n, java.util.function.Supplier<double[]> gen, int variant) {
        RootsUlpEval2.VARIANT = variant;
        double[] r = new double[4];
        ArrayList<Double> dists = new ArrayList<>();
        long noneReturned = 0, polys = 0, illCond = 0;
        double worst = 0; String worstCase = "";
        for (int i = 0; i < n; i++) {
            double[] rr = gen.get();
            double d = scale();
            double a = -d * (rr[0] + rr[1] + rr[2]);
            double b = d * (rr[0] * rr[1] + rr[0] * rr[2] + rr[1] * rr[2]);
            double c = -d * rr[0] * rr[1] * rr[2];
            int k = RootsUlpEval2.cubic(d, a, b, c, r, 0, A, B);
            List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
            if (refs == null) { illCond++; continue; }
            polys++;
            for (BigDecimal e : refs) {
                if (e.compareTo(new BigDecimal(A)) < 0 || e.compareTo(new BigDecimal(B)) >= 0) continue;
                if (k == 0) { noneReturned++; continue; }
                double ed = e.doubleValue(), best = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) best = Math.min(best, Math.abs(r[j] - ed));
                dists.add(best);
                if (best > worst) {
                    worst = best;
                    worstCase = String.format("d=%s a=%s b=%s c=%s ref=%s returned=%s", d, a, b, c,
                            e.round(new java.math.MathContext(17)), Arrays.toString(Arrays.copyOf(r, k)));
                }
            }
        }
        Collections.sort(dists);
        System.out.printf("%-42s %-9s polys=%5d illCond=%3d roots=%5d noneReturned=%3d | median=%8.2g p90=%8.2g p99=%8.2g MAX=%8.3g  frac>1e-6: %5.1f%%  frac>1e-3: %5.1f%%%n",
                name, variant == 0 ? "original" : variant == 1 ? "relTol" : "relTol+v", polys, illCond, dists.size(), noneReturned,
                q(dists, 0.5), q(dists, 0.9), q(dists, 0.99), worst, fr(dists, 1e-6), fr(dists, 1e-3));
        if (worst > 1e-3) System.out.println("      worst: " + worstCase);
    }
    static double q(List<Double> l, double p) { return l.isEmpty() ? Double.NaN : l.get((int) Math.min(l.size() - 1, Math.floor(p * l.size()))); }
    static double fr(List<Double> l, double t) { long k = 0; for (double v : l) if (v > t) k++; return l.isEmpty() ? Double.NaN : 100.0 * k / l.size(); }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        System.out.println("Distance from each true root in [1e-6, 1-1e-6) to the nearest root cubicRootsInAB returned (no cap).\n");
        for (int v = 0; v <= 2; v++) {
            rnd = new Random(0x5EEDL);
            run("3 roots uniform in [A,B)", N, () -> new double[]{uni(A, B), uni(A, B), uni(A, B)}, v);
        }
        System.out.println();
        for (int v = 0; v <= 2; v++) {
            rnd = new Random(0x5EEDL);
            run("3 roots in [1e-6,0.1]", N, () -> new double[]{uni(A, 0.1), uni(A, 0.1), uni(A, 0.1)}, v);
        }
        System.out.println();
        for (int v = 0; v <= 2; v++) {
            rnd = new Random(0x5EEDL);
            run("close pair gap=1e-2 + 1 root", N, () -> { double r1 = uni(0.1, 0.9); return new double[]{r1, r1 + 1e-2, uni(0.1, 0.9)}; }, v);
        }
        System.out.println();
        for (int v = 0; v <= 2; v++) {
            rnd = new Random(0x5EEDL);
            run("3 roots log-uniform in [1e-6,1)", N, () -> new double[]{logUni(A, B), logUni(A, B), logUni(A, B)}, v);
        }
    }
}
