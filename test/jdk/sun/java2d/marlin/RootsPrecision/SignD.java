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
import java.math.MathContext;
import java.util.*;

/**
 * How to get the sign of D right.
 *
 * Forming D = q^2 + p^3 (or qh^2 + 4ph^3) explicitly is hopeless in the hard cases:
 * the terms reach 1e60 and cancel down to 1e20, past what double-double holds.
 *
 * But sign(D) does not require D. D < 0 exactly when f has three distinct real roots,
 * which happens exactly when f takes opposite signs at its two critical points:
 *
 *     sign(D) == sign( f(t1) * f(t2) ),   f'(t1) = f'(t2) = 0
 *
 * Both ingredients are now accurate: t1,t2 come from quadraticRoots on f' (Kahan
 * discriminant, <= 2 ulps) and f is evaluated by compensated Horner. And because
 * f'(t1) = 0, an error d in t1 moves f(t1) only by f''(t1)*d^2/2 -- second order, so
 * the inaccuracy of the critical points barely matters.
 */
public class SignD {

    static double twoSumErr(double a, double b, double s) { double bv = s - a; return (a - (s - bv)) + (b - bv); }

    static double discriminant(double a, double b, double c) {
        double a4 = 4.0 * a, p = b * b, q = a4 * c;
        return (p - q) + (Math.fma(b, b, -p) - Math.fma(a4, c, -q));
    }

    static int quadraticRoots(double a, double b, double c, double[] z, int off) {
        int ret = off;
        if (a != 0.0) {
            double d = discriminant(a, b, c);
            if (d > 0.0) {
                d = Math.sqrt(d);
                if (b < 0.0) d = -d;
                double q = (b + d) / -2.0;
                z[ret++] = q / a;
                if (q != 0.0) z[ret++] = c / q;
            } else if (d == 0.0) z[ret++] = -b / (2.0 * a);
        } else if (b != 0.0) z[ret++] = -c / b;
        return ret - off;
    }

    static double compHorner(double d, double a, double b, double c, double t) {
        double s = d, e = 0.0;
        double pr = s * t, sm = pr + a;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, a, sm)); s = sm;
        pr = s * t; sm = pr + b;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, b, sm)); s = sm;
        pr = s * t; sm = pr + c;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, c, sm));
        return sm + e;
    }
    static double plainHorner(double d, double a, double b, double c, double t) {
        return Math.fma(Math.fma(Math.fma(d, t, a), t, b), t, c);
    }

    /** method 0 = compensated Horner at the critical points; 1 = plain fma Horner there */
    public static int signD(double d, double a, double b, double c, int method) {
        double[] cp = new double[2];
        int n = quadraticRoots(3.0 * d, 2.0 * a, b, cp, 0);     // f'(t) = 3d t^2 + 2a t + b
        if (n == 0) return 1;                                   // strictly monotone: one real root
        if (n == 1) {                                           // inflection with horizontal tangent
            double f = (method == 0) ? compHorner(d, a, b, c, cp[0]) : plainHorner(d, a, b, c, cp[0]);
            return (f == 0.0) ? 0 : 1;
        }
        double f1 = (method == 0) ? compHorner(d, a, b, c, cp[0]) : plainHorner(d, a, b, c, cp[0]);
        double f2 = (method == 0) ? compHorner(d, a, b, c, cp[1]) : plainHorner(d, a, b, c, cp[1]);
        if (f1 == 0.0 || f2 == 0.0) return 0;
        return ((f1 > 0.0) == (f2 > 0.0)) ? 1 : -1;
    }

    /** the committed code's sign: compensated p,q,p^3,D then look at D */
    public static int signCompensatedD(double dd, double a, double b, double c) {
        a /= dd; b /= dd; c /= dd;
        double sq_A = a * a, sq_A_err = Math.fma(a, a, -sq_A);
        double cb_A = sq_A * a, cb_A_err = Math.fma(sq_A, a, -cb_A) + sq_A_err * a;
        double ab = a * b, ab_err = Math.fma(a, b, -ab);
        double b3 = 3.0 * b, b3_err = Math.fma(3.0, b, -b3);
        double ps = b3 - sq_A;
        double p = (ps + ((b3_err - sq_A_err) + twoSumErr(b3, -sq_A, ps))) / 9.0;
        double t1 = 2.0 * cb_A, t1e = 2.0 * cb_A_err;
        double t2 = 9.0 * ab, t2e = Math.fma(9.0, ab, -t2) + 9.0 * ab_err;
        double t3 = 27.0 * c, t3e = Math.fma(27.0, c, -t3);
        double qs1 = t1 - t2, qs2 = qs1 + t3;
        double q = (qs2 + (((t1e - t2e) + t3e) + (twoSumErr(t1, -t2, qs1) + twoSumErr(qs1, t3, qs2)))) / 54.0;
        double sq_p = p * p, sq_p_err = Math.fma(p, p, -sq_p);
        double cb_p_hi = sq_p * p, cb_p_err = Math.fma(sq_p, p, -cb_p_hi) + sq_p_err * p;
        double sq_q = q * q, sq_q_err = Math.fma(q, q, -sq_q);
        double Ds = sq_q + cb_p_hi;
        double D = Ds + ((sq_q_err + cb_p_err) + twoSumErr(sq_q, cb_p_hi, Ds));
        return (D > 0.0) ? 1 : (D < 0.0 ? -1 : 0);
    }


    /** the compensated p and q of the committed code, returned as a pair */
    static double[] compensatedPQ(double dd, double a, double b, double c) {
        a /= dd; b /= dd; c /= dd;
        double sq_A = a * a, sq_A_err = Math.fma(a, a, -sq_A);
        double cb_A = sq_A * a, cb_A_err = Math.fma(sq_A, a, -cb_A) + sq_A_err * a;
        double ab = a * b, ab_err = Math.fma(a, b, -ab);
        double b3 = 3.0 * b, b3_err = Math.fma(3.0, b, -b3);
        double ps = b3 - sq_A;
        double p = (ps + ((b3_err - sq_A_err) + twoSumErr(b3, -sq_A, ps))) / 9.0;
        double t1 = 2.0 * cb_A, t1e = 2.0 * cb_A_err;
        double t2 = 9.0 * ab, t2e = Math.fma(9.0, ab, -t2) + 9.0 * ab_err;
        double t3 = 27.0 * c, t3e = Math.fma(27.0, c, -t3);
        double qs1 = t1 - t2, qs2 = qs1 + t3;
        double q = (qs2 + (((t1e - t2e) + t3e) + (twoSumErr(t1, -t2, qs1) + twoSumErr(qs1, t3, qs2)))) / 54.0;
        return new double[]{ p, q };
    }

    /**
     * sign(q^2 + p^3) by the difference-of-squares factorisation instead of the
     * difference itself. For p >= 0 both terms are non-negative and there is nothing
     * to cancel. For p < 0, with P = -p:
     *
     *     q^2 - P^3 = (|q| - P^(3/2)) * (|q| + P^(3/2))
     *
     * the right factor is a sum of positives, so the sign is that of the left one.
     * Comparing magnitudes rather than subtracting squares halves the digits needed:
     * if q^2 and P^3 agree to 40 digits, |q| and P^(3/2) agree to only 20.
     * dd = true computes P^(3/2) in double-double.
     */
    static int signMag(double d, double a, double b, double c, boolean dd) {
        double[] pq = compensatedPQ(d, a, b, c);
        double p = pq[0], q = pq[1];
        if (p >= 0.0) {
            if (p == 0.0 && q == 0.0) return 0;
            return 1;                       // q^2 + p^3 >= 0 with equality only at 0
        }
        double P = -p, aq = Math.abs(q);
        if (!dd) {
            double bmag = P * Math.sqrt(P);
            return (aq > bmag) ? 1 : ((aq < bmag) ? -1 : 0);
        }
        double s = Math.sqrt(P);
        double slo = Math.fma(-s, s, P) / (2.0 * s);       // sqrt refined to double-double
        double h = P * s;                                   // (P,0) * (s,slo)
        double l = Math.fma(P, s, -h) + P * slo;
        double hh = h + l, ll = l - (hh - h);               // renormalise
        if (aq > hh) return 1;
        if (aq < hh) return -1;
        if (ll < 0.0) return 1;                             // aq - (hh+ll) = -ll
        if (ll > 0.0) return -1;
        return 0;
    }

    static Random rnd;
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double sc(int h) { double s = Math.pow(10.0, uni(-h, h)); return rnd.nextBoolean() ? s : -s; }

    /** exact sign of the cubic discriminant, in 120 digits */
    public static int exactSign(double d, double a, double b, double c) {
        MathContext mc = new MathContext(120);
        BigDecimal D = new BigDecimal(d), A = new BigDecimal(a), B = new BigDecimal(b), C = new BigDecimal(c);
        // Delta = 18abcd - 4a^3c + a^2b^2 - 4b^3d - 27c^2d^2 for d x^3 + a x^2 + b x + c ; D = -Delta/(108 d^4)
        BigDecimal delta = BigDecimal.valueOf(18).multiply(A).multiply(B).multiply(C).multiply(D)
                .subtract(BigDecimal.valueOf(4).multiply(A.pow(3)).multiply(C))
                .add(A.pow(2).multiply(B.pow(2)))
                .subtract(BigDecimal.valueOf(4).multiply(B.pow(3)).multiply(D))
                .subtract(BigDecimal.valueOf(27).multiply(C.pow(2)).multiply(D.pow(2)));
        return -delta.signum();     // d^4 > 0
    }

    /** cubics with a deliberately near-double root: sign(D) is genuinely delicate here */
    static void nearDegenerate(int N) {
        System.out.printf("%n%-30s %8s %8s %8s %8s %8s %8s %8s%n", "near-double root, gap =", "gap", "checked", "formD", "plainH", "compH", "magD", "magDD");
        double[] gaps = { 1e-2, 1e-4, 1e-6, 1e-8, 1e-10, 1e-12, 0.0 };
        for (double gap : gaps) {
            long checked = 0, wD = 0, wP = 0, wC = 0, wM = 0, wMM = 0;
            rnd = new Random(31337L);
            for (int i = 0; i < N; i++) {
                double r = uni(0.05, 0.95), s = uni(-2, 2), d = sc(4);
                double r2 = r + gap;
                double a = -d * (r + r2 + s);
                double b = d * (r * r2 + r * s + r2 * s);
                double c = -d * r * r2 * s;
                if (d == 0.0) continue;
                int ex = exactSign(d, a, b, c);
                checked++;
                if (signCompensatedD(d, a, b, c) != ex) wD++;
                if (signD(d, a, b, c, 1) != ex) wP++;
                if (signD(d, a, b, c, 0) != ex) wC++;
                if (signMag(d, a, b, c, false) != ex) wM++;
                if (signMag(d, a, b, c, true) != ex) wMM++;
            }
            System.out.printf("%-30s %8.0e %8d %8d %8d %8d %8d %8d%n", "", gap, checked, wD, wP, wC, wM, wMM);
        }
    }

    public static void main(String[] args) {
        int N = args.length > 0 ? Integer.parseInt(args[0]) : 200000;
        int[] halves = { 3, 6, 10, 15 };
        System.out.printf("%-34s %10s %12s %12s %12s%n", "sign(D) method", "decades", "checked", "wrong sign", "rate");
        for (int h : halves) {
            long[] wrong = new long[6];
            long checked = 0;
            rnd = new Random(99L);
            for (int i = 0; i < N; i++) {
                double d = sc(h), a = sc(h), b = sc(h), c = sc(h);
                if (d == 0.0) continue;
                int ex = exactSign(d, a, b, c);
                checked++;
                if (signCompensatedD(d, a, b, c) != ex) wrong[0]++;
                if (EFTSolve.discriminantSign(d, a, b, c) != ex) wrong[1]++;
                if (signD(d, a, b, c, 1) != ex) wrong[2]++;
                if (signD(d, a, b, c, 0) != ex) wrong[3]++;
                if (signMag(d, a, b, c, false) != ex) wrong[4]++;
                if (signMag(d, a, b, c, true) != ex) wrong[5]++;
            }
            String[] names = { "form D = q^2+p^3 (double)", "double-double qh^2+4ph^3",
                               "critical points, plain Horner", "critical points, comp. Horner",
                               "|q| vs P^(3/2), double", "|q| vs P^(3/2), double-double" };
            for (int m = 0; m < 6; m++)
                System.out.printf("%-34s %10d %12d %12d %11.4f%%%n", names[m], 2 * h, checked, wrong[m], 100.0 * wrong[m] / checked);
            System.out.println();
        }
        nearDegenerate(20000);
    }
}
