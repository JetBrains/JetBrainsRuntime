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
 * For each true root in [A,B) that the committed solver loses on wild coefficients,
 * how much cancellation does the reconstruction root = t*cos(phi) - sub suffer?
 * ratio = |sub| / |root| is the number of digits the subtraction must cancel away.
 */
public class WhyLost {
    static Random rnd; static final double A = 1e-6, B = 1.0 - 1e-6, TOL = 1e-6;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double sc(int h) { double s = Math.pow(10.0, uni(-h, h)); return rnd.nextBoolean() ? s : -s; }
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 40000;
        rnd = new Random(4242L);
        double[] got = new double[4];
        ArrayList<Double> ratios = new ArrayList<>();
        long lost = 0, kept = 0;
        ArrayList<Double> keptRatios = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            double d = sc(10), a = sc(10), b = sc(10), c = sc(10);
            List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
            if (refs == null || d == 0.0) continue;
            int k = EFTSolve.solve(d, a, b, c, got, 0, A, B, 4);
            double sub = Math.abs((a / d) / 3.0);
            for (BigDecimal e : refs) {
                double ed = e.doubleValue();
                if (ed < A || ed >= B) continue;
                double best = Double.MAX_VALUE;
                for (int j = 0; j < k; j++) best = Math.min(best, Math.abs(got[j] - ed));
                double ratio = sub / Math.abs(ed);
                if (best > TOL) { lost++; ratios.add(ratio); }
                else { kept++; keptRatios.add(ratio); }
            }
        }
        Collections.sort(ratios); Collections.sort(keptRatios);
        System.out.printf("lost roots: %d, kept roots: %d%n", lost, kept);
        System.out.printf("%-14s %10s %10s %10s %10s %10s%n", "|sub|/|root|", "p10", "median", "p90", "p99", "max");
        p("lost", ratios); p("kept", keptRatios);
        long above = 0; for (double r : ratios) if (r > 1e16) above++;
        System.out.printf("%nlost roots with |sub|/|root| > 1e16 (reconstruction cancels past double): %d of %d (%.1f%%)%n",
                above, ratios.size(), 100.0 * above / Math.max(1, ratios.size()));
        long keptAbove = 0; for (double r : keptRatios) if (r > 1e16) keptAbove++;
        System.out.printf("kept roots with the same: %d of %d (%.2f%%)%n", keptAbove, keptRatios.size(), 100.0 * keptAbove / Math.max(1, keptRatios.size()));
    }
    static void p(String n, List<Double> l) {
        if (l.isEmpty()) { System.out.println(n + ": none"); return; }
        System.out.printf("%-14s %10.2g %10.2g %10.2g %10.2g %10.2g%n", n,
                l.get(l.size()/10), l.get(l.size()/2), l.get(l.size()*9/10), l.get(Math.min(l.size()-1, l.size()*99/100)), l.get(l.size()-1));
    }
}
