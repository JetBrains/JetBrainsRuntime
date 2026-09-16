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

public class Bench {
    public static void main(String[] args) {
        int N = 2_000_000;
        double[] pts = new double[8];
        java.util.Random r = new java.util.Random(7L);
        double[] cs = new double[4 * N];
        for (int i = 0; i < N; i++) {
            double x1 = r.nextDouble() * 4096, x2 = r.nextDouble() * 4096, x3 = r.nextDouble() * 4096, x4 = r.nextDouble() * 4096;
            cs[4*i]   = 3.0 * (x2 - x3) + x4 - x1;
            cs[4*i+1] = 3.0 * (x1 - 2.0 * x2 + x3);
            cs[4*i+2] = 3.0 * (x2 - x1);
            cs[4*i+3] = x1 - (r.nextDouble() * 4096);
        }
        for (int pass = 0; pass < 4; pass++) {
            long t0 = System.nanoTime(); int sink = 0;
            for (int i = 0; i < N; i++) sink += FinalHelpers.cubicRootsInAB(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0, 1e-6, 1.0 - 1e-6);
            long t1 = System.nanoTime(); int sink2 = 0;
            for (int i = 0; i < N; i++) sink2 += EFTSolve.solve(cs[4*i], cs[4*i+1], cs[4*i+2], cs[4*i+3], pts, 0, 1e-6, 1.0 - 1e-6, 4);
            long t2 = System.nanoTime();
            if (pass == 3) System.out.printf("bracketed (shipped): %.1f ns/solve | closed form only (previous): %.1f ns/solve | ratio %.2fx  [roots %d vs %d]%n",
                    (t1 - t0) / (double) N, (t2 - t1) / (double) N, (t1 - t0) / (double) (t2 - t1), sink, sink2);
        }
    }
}
