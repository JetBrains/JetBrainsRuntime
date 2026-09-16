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

public class QuadShipped {
    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        StringBuilder sb = new StringBuilder();
        RootsUlpEval3.Quad s = FinalHelpers::quadraticRoots;
        sb.append("== shipped quadraticRoots: Kahan discriminant, no Newton ==\n");
        RootsUlpEval3.quadScenario("quad: 2 roots uniform in [A,B)", N, s,
                () -> new double[]{ RootsUlpEval3.uni(RootsUlpEval3.A, RootsUlpEval3.B),
                                    RootsUlpEval3.uni(RootsUlpEval3.A, RootsUlpEval3.B) }, sb);
        for (int k : new int[]{ 2, 4, 6 }) {
            final double delta = Math.pow(10, -k);
            RootsUlpEval3.quadScenario("quad: close roots gap=1e-" + k, N, s,
                    () -> { double r1 = RootsUlpEval3.uni(0.1, 0.9); return new double[]{ r1, r1 + delta }; }, sb);
        }
        RootsUlpEval3.quadMarlinDx("quad: Marlin dxRoots [0,4096]", N, s, 4096, sb);
        System.out.print(sb);
    }
}
