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
/** Scan for NaN / infinite roots and for root-count regressions vs the current code. */
public class NanScan {
    static Random rnd = new Random(12345L);
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double scale() { int h = Integer.getInteger("half", 10); double s = Math.pow(10.0, uni(-h, h)); return rnd.nextBoolean() ? s : -s; }
    public static void main(String[] a) {
        double A = 1e-6, B = 1.0 - 1e-6;
        double[] r0 = new double[4], r1 = new double[4], r2 = new double[4];
        long nan = 0, n = 0, cnt0 = 0, cnt2 = 0, moreRoots = 0, fewerRoots = 0;
        int N = a.length > 0 ? Integer.parseInt(a[0]) : 2000000;
        for (int i = 0; i < N; i++) {
            double d, p, q, c;
            int shape = i % 5;
            switch (shape) {
                case 0 -> { d = scale(); p = scale(); q = scale(); c = scale(); }       // wild
                case 1 -> { double x = uni(0,1), y = uni(0,1), z = uni(0,1); d = scale();
                            p = -d*(x+y+z); q = d*(x*y+x*z+y*z); c = -d*x*y*z; }        // 3 roots in [0,1)
                case 2 -> { double x = uni(0,1e-3), y = x, z = uni(0,1); d = scale();
                            p = -d*(x+y+z); q = d*(x*y+x*z+y*z); c = -d*x*y*z; }        // double root, tiny
                case 3 -> { double x = uni(0,1); d = scale();
                            p = -3*d*x; q = 3*d*x*x; c = -d*x*x*x; }                     // triple root
                default -> { d = 0.0; p = scale(); q = scale(); c = scale(); }           // degenerate to quadratic
            }
            int k0 = Orig.cubic(d, p, q, c, r0, 0, A, B);
            int k2 = EFTSolve.solve(d, p, q, c, r2, 0, A, B, 4);
            cnt0 += k0; cnt2 += k2; n++;
            for (int j = 0; j < k2; j++) {
                if (Double.isNaN(r2[j]) || Double.isInfinite(r2[j])) { nan++;
                    if (nan <= 3) System.out.printf("NaN/Inf: d=%s a=%s b=%s c=%s -> %s%n", d, p, q, c, Arrays.toString(Arrays.copyOf(r2, k2)));
                }
            }
            if (k2 > k0) moreRoots++; else if (k2 < k0) fewerRoots++;
        }
        System.out.printf("%d polynomials: NaN/Inf roots = %d | roots returned: current=%d patched=%d | polys where patched returns more=%d fewer=%d%n",
                n, nan, cnt0, cnt2, moreRoots, fewerRoots);
    }
    /** current Helpers.java, for comparison */
    static class Orig {
        static final double EPS = 1e-9d;
        static boolean within(double x, double y) { double t = y - x; return t <= EPS && t >= -EPS; }
        static int cubic(double dd, double a, double b, double c, double[] pts, int off, double A, double B) {
            if (dd == 0.0d) {
                int num = RootsUlpEval3.quadOrig(a, b, c, pts, off);
                return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
            }
            a /= dd; b /= dd; c /= dd;
            double sq_A = a * a;
            double p = (1.0d/3.0d)*((-1.0d/3.0d)*sq_A + b);
            double sub = (1.0d/3.0d)*a;
            double q = (1.0d/2.0d)*((2.0d/27.0d)*a*sq_A - sub*b + c);
            double cb_p = p*p*p, D = q*q + cb_p;
            int num;
            if (within(D, 0.0d)) {
                if (within(q, 0.0d)) { pts[off] = -sub; num = 1; }
                else { double u = Math.cbrt(-q); pts[off] = 2.0d*u - sub; pts[off+1] = -u - sub; num = 2; }
            } else if (D < 0.0d) {
                double phi = (1.0d/3.0d)*Math.acos(-q/Math.sqrt(-cb_p));
                double t = 2.0d*Math.sqrt(-p);
                pts[off] = t*Math.cos(phi) - sub;
                pts[off+1] = -t*Math.cos(phi + Math.PI/3.0d) - sub;
                pts[off+2] = -t*Math.cos(phi - Math.PI/3.0d) - sub;
                num = 3;
            } else {
                double sD = Math.sqrt(D);
                pts[off] = Math.cbrt(sD - q) - Math.cbrt(sD + q) - sub; num = 1;
            }
            return RootsUlpEval2.filterOutNotInAB(pts, off, num, A, B) - off;
        }
    }
}
