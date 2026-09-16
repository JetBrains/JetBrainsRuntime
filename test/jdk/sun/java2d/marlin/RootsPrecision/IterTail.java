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
/** How many iterations does solveBracket actually consume? The tail decides the cap. */
public class IterTail {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static long[] hist = new long[200];
    static long roots = 0;

    static double refine(double d, double a, double b, double c, double lo, double hi, double flo, double fhi) {
        double t = lo - flo * (hi - lo) / (fhi - flo);
        if (!(t > lo && t < hi)) t = 0.5 * (lo + hi);
        double best = t, fbest = Double.POSITIVE_INFINITY;
        boolean negLo = flo < 0.0;
        int it = 0;
        for (; it < 190; it++) {
            double f = FinalHelpers.compHorner(d, a, b, c, t);
            double af = Math.abs(f);
            if (af < fbest) { fbest = af; best = t; }
            if (f == 0.0) break;
            double fp = Math.fma(Math.fma(3.0 * d, t, 2.0 * a), t, b);
            double nt = (fp != 0.0) ? t - f / fp : Double.NaN;
            if ((f < 0.0) == negLo) lo = t; else hi = t;
            if (!Double.isFinite(nt) || nt <= lo || nt >= hi) {
                nt = 0.5 * (lo + hi);
                if (nt <= lo || nt >= hi) break;
            }
            if (nt == t) break;
            t = nt;
        }
        hist[Math.min(it + 1, 199)]++; roots++;
        return best;
    }

    static int solve(double d, double a, double b, double c, double[] pts, int off) {
        if (d == 0.0) return 0;
        double[] cp = new double[2];
        int ncp = FinalHelpers.criticalPoints(d, a, b, cp);
        double[] bnd = new double[4];
        int nb = 0; bnd[nb++] = A;
        for (int i = 0; i < ncp; i++) if (cp[i] > A && cp[i] < B && cp[i] > bnd[nb-1]) bnd[nb++] = cp[i];
        bnd[nb++] = B;
        int num = 0;
        double flo = FinalHelpers.compHorner(d, a, b, c, bnd[0]);
        for (int i = 0; i + 1 < nb; i++) {
            double lo = bnd[i], hi = bnd[i+1];
            double fhi = FinalHelpers.compHorner(d, a, b, c, hi);
            if (flo != 0.0 && fhi != 0.0 && ((flo < 0.0) != (fhi < 0.0))) {
                double t = refine(d, a, b, c, lo, hi, flo, fhi);
                if (t >= A && t < B && num < 3) pts[off + num++] = t;
            }
            flo = fhi;
        }
        return num;
    }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 400000;
        double[] pts = new double[8];
        System.out.printf("%-9s %10s %8s %8s %8s %8s %8s%n", "decades", "roots", "mean", "p99", "p99.9", "p99.99", "max");
        for (int D : new int[]{ 3, 10, 15, 30, 50 }) {
            Arrays.fill(hist, 0); roots = 0;
            Random r = new Random(4242L + D);
            for (int i = 0; i < N; i++) {
                double d = mag(r, D), a = mag(r, D), b = mag(r, D), c = mag(r, D);
                if (d == 0.0 || !Double.isFinite(d + a + b + c)) continue;
                solve(d, a, b, c, pts, 0);
            }
            long sum = 0; for (int i = 0; i < 200; i++) sum += (long) i * hist[i];
            System.out.printf("%-9d %10d %8.1f %8d %8d %8d %8d%n", D, roots, sum / (double) Math.max(1, roots),
                    q(0.99), q(0.999), q(0.9999), maxIt());
        }
        // Marlin's own shapes
        for (int sh : new int[]{ 6, 5 }) {
            Arrays.fill(hist, 0); roots = 0;
            BracketSolve.rnd = new Random(31337L);
            for (int i = 0; i < N; i++) {
                double[] co = BracketSolve.shape(sh);
                if (co[0] == 0.0 || !Double.isFinite(co[0] + co[1] + co[2] + co[3])) continue;
                solve(co[0], co[1], co[2], co[3], pts, 0);
            }
            long sum = 0; for (int i = 0; i < 200; i++) sum += (long) i * hist[i];
            System.out.printf("%-9s %10d %8.1f %8d %8d %8d %8d%n", sh == 6 ? "px xPts" : "perp1e30", roots,
                    sum / (double) Math.max(1, roots), q(0.99), q(0.999), q(0.9999), maxIt());
        }
    }
    static double mag(Random r, int d) { double v = Math.pow(10.0, (r.nextDouble() - 0.5) * d); return r.nextBoolean() ? v : -v; }
    static int q(double p) { long acc = 0, target = (long) Math.ceil(p * roots); for (int i = 0; i < 200; i++) { acc += hist[i]; if (acc >= target) return i; } return 199; }
    static int maxIt() { for (int i = 199; i >= 0; i--) if (hist[i] > 0) return i; return 0; }
}
