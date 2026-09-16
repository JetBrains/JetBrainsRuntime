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
/** Full 8-shape suite for each candidate stop rule, plus cost. */
public class StopSuite {
    public static void main(String[] args) throws Exception {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 12000;
        String[] nm = { "none (cap 90 only)", "residual floor", "-", "floor + bracket<=1e-9", "floor + bracket<=1e-12" };
        String[] shapes = { "40 decades", "30 decades", "20 decades", "tiny 1e-15..1e-5",
                            "3 roots, d 40 dec", "perp 1e-7..1e30", "xPoints px", "close pair 1e-6" };
        int[] ids = { 0, 1, 2, 3, 4, 5, 6, 7 };
        for (int rule : new int[]{ 0, 3, 4 }) {
            StopRule.RULE = rule; StopRule.CAP = 90;
            StopRule.A = 1e-6; StopRule.B = 1.0 - 1e-6;
            StringBuilder sb = new StringBuilder();
            long totLost = 0, totSpur = 0;
            for (int si = 0; si < ids.length; si++) {
                BracketSolve.rnd = new Random(777333L);
                long lost = 0, spur = 0, ok = 0, tot = 0;
                double[] got = new double[8];
                for (int i = 0; i < N; i++) {
                    double[] co = BracketSolve.shape(ids[si]);
                    double d = co[0], a = co[1], b = co[2], c = co[3];
                    if (d == 0.0 || !Double.isFinite(d+a+b+c)) continue;
                    List<BigDecimal> refs = RootsUlpEval2.referenceRoots(d, a, b, c);
                    if (refs == null) continue;
                    int k = StopRule.solve(d, a, b, c, got, 0);
                    boolean[] used = new boolean[Math.max(k,1)];
                    for (BigDecimal e : refs) {
                        double ed = e.doubleValue();
                        if (ed < StopRule.A || ed >= StopRule.B) continue;
                        int best = -1; double bd = Double.MAX_VALUE;
                        for (int j = 0; j < k; j++) if (!used[j] && Math.abs(got[j]-ed) < bd) { bd = Math.abs(got[j]-ed); best = j; }
                        if (best < 0 || bd > 1e-6) { lost++; continue; }
                        used[best] = true; tot++;
                        if (e.subtract(new BigDecimal(got[best]), RootsUlpEval2.MC).abs().doubleValue()/Math.ulp(ed) <= 0.5) ok++;
                    }
                    for (int j = 0; j < k; j++) {
                        if (used[j]) continue;
                        boolean near = false;
                        for (BigDecimal e : refs) if (Math.abs(got[j]-e.doubleValue()) <= 1e-6) near = true;
                        if (!near) spur++;
                    }
                }
                totLost += lost; totSpur += spur;
                sb.append(String.format("  %-20s lost=%-6d spur=%-6d <=0.5ulp=%.2f%%%n", shapes[si], lost, spur, tot==0?Double.NaN:100.0*ok/tot));
            }
            System.out.printf("== %s ==  total lost=%d spurious=%d%n%s", nm[rule], totLost, totSpur, sb);
        }
    }
}
