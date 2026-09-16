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
 * Which cheap quantity separates the polynomials where the closed form is safe from the
 * ones where it loses a root? Candidates, all computed from the coefficients:
 *   S    = |d|+|a|+|b|+|c|                      the raw scale (not scale-invariant)
 *   DR   = max|coef| / min nonzero |coef|       dynamic range inside the polynomial
 *   SUB  = |a/(3d)|                             the shift the reconstruction cancels against
 *   MAXN = max(|a/d|,|b/d|,|c/d|)               normalised coefficient size
 *   COND = sum|coef_i t^i| / |t f'(t)|          the root's own condition number
 * Reported as the distribution over polynomials where the closed form LOST a root versus
 * where it did not.
 */
public class Discrim {
    static final double A = 1e-6, B = 1.0 - 1e-6;
    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 40000;
        String[] names = { "perpendiculardfddf device", "xPoints device", "20-decade coeffs", "perp 1e-7..1e30" };
        String[] mnames = { "S=sum|coef|", "DR=max/min", "SUB=|a/3d|", "MAXN=max|c/d|", "COND=root cond" };
        for (int sh = 0; sh < 4; sh++) {
            BracketSolve.rnd = new Random(556677L);
            rnd = new Random(556677L);
            ArrayList<double[]> fail = new ArrayList<>(), pass = new ArrayList<>();
            double[] got = new double[8], refv = new double[8];
            for (int i = 0; i < N; i++) {
                double[] co = sample(sh);
                if (co == null) continue;
                double d = co[0], a = co[1], b = co[2], c = co[3];
                CapProper.CAP = 200; CapProper.REL = 0.0;
                int kr = CapProper.solve(d, a, b, c, refv, 0);
                if (kr == 0) continue;
                int kf = EFTSolve.solve(d, a, b, c, got, 0, A, B, 4);
                boolean lostOne = false;
                double worstCond = 0;
                for (int j = 0; j < kr; j++) {
                    double ed = refv[j], bd = Double.MAX_VALUE;
                    for (int q = 0; q < kf; q++) bd = Math.min(bd, Math.abs(got[q] - ed));
                    if (bd > 1e-6) lostOne = true;
                    worstCond = Math.max(worstCond, cond(d, a, b, c, ed));
                }
                double S = Math.abs(d)+Math.abs(a)+Math.abs(b)+Math.abs(c);
                double mx = Math.max(Math.max(Math.abs(d),Math.abs(a)), Math.max(Math.abs(b),Math.abs(c)));
                double mn = Double.MAX_VALUE;
                for (double v : new double[]{d,a,b,c}) if (v != 0.0) mn = Math.min(mn, Math.abs(v));
                double DR = (mn == Double.MAX_VALUE) ? 1.0 : mx / mn;
                double[] m = { S, DR, Math.abs(a/(3.0*d)),
                               Math.max(Math.max(Math.abs(a/d),Math.abs(b/d)),Math.abs(c/d)), worstCond };
                (lostOne ? fail : pass).add(m);
            }
            System.out.printf("%n#### %s : %d polys where the closed form lost a root, %d where it did not%n",
                    names[sh], fail.size(), pass.size());
            System.out.printf("%-16s %-42s %-42s%n", "metric", "LOST: p1 / median / p99 / max", "OK: p1 / median / p99 / max");
            for (int mi = 0; mi < 5; mi++) {
                System.out.printf("%-16s %-42s %-42s%n", mnames[mi], q(fail, mi), q(pass, mi));
            }
        }
    }
    static double cond(double d, double a, double b, double c, double t) {
        double t2 = t*t, t3 = t2*t;
        double num = Math.abs(d*t3)+Math.abs(a*t2)+Math.abs(b*t)+Math.abs(c);
        double fp = Math.fma(Math.fma(3.0*d, t, 2.0*a), t, b);
        double den = Math.abs(t*fp);
        return den == 0.0 ? Double.POSITIVE_INFINITY : num/den;
    }
    static String q(List<double[]> l, int mi) {
        if (l.isEmpty()) return "-";
        double[] v = new double[l.size()];
        for (int i = 0; i < v.length; i++) v[i] = l.get(i)[mi];
        Arrays.sort(v);
        return String.format("%9.2e %9.2e %9.2e %9.2e", v[v.length/100], v[v.length/2],
                v[Math.min(v.length-1, v.length*99/100)], v[v.length-1]);
    }
    static double[] sample(int sh) {
        if (sh == 2) { double[] co = BracketSolve.shape(2); return bad(co) ? null : co; }
        if (sh == 3) { double[] co = BracketSolve.shape(5); return bad(co) ? null : co; }
        double span = 4096.0;
        double[] X = new double[4], Y = new double[4];
        for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
        double ax = 3.0*(X[1]-X[2])+X[3]-X[0], bx = 3.0*(X[0]-2.0*X[1]+X[2]), cx = 3.0*(X[1]-X[0]);
        double ay = 3.0*(Y[1]-Y[2])+Y[3]-Y[0], by = 3.0*(Y[0]-2.0*Y[1]+Y[2]), cy = 3.0*(Y[1]-Y[0]);
        if (sh == 1) {
            double lo = Math.min(Math.min(X[0],X[1]),Math.min(X[2],X[3])), hi = Math.max(Math.max(X[0],X[1]),Math.max(X[2],X[3]));
            double[] co = { ax, bx, cx, X[0] - uni(lo, hi) };
            return bad(co) ? null : co;
        }
        double dax = 3*ax, dbx = 2*bx, day = 3*ay, dby = 2*by;
        double[] co = { 2.0*(dax*dax+day*day), 3.0*(dax*dbx+day*dby),
                        2.0*(dax*cx+day*cy)+dbx*dbx+dby*dby, dbx*cx+dby*cy };
        return bad(co) ? null : co;
    }
    static boolean bad(double[] co) { return co[0] == 0.0 || !Double.isFinite(co[0]+co[1]+co[2]+co[3]); }
}
