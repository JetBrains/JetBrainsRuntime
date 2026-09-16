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

public final class FinalHelpers {
    private static final double EPS = 1e-9d;
    private static final double REFINE_EPS = 1e-11;
    static boolean within(final double x, final double y) { return withinD(y - x, EPS); }
    static boolean withinD(final double d, final double err) { return (d <= err && d >= -err); }

    public static double twoSumErr(final double x, final double y, final double s) {
        final double bv = s - x;
        return (x - (s - bv)) + (y - bv);
    }

    public static double compHorner(final double d, final double a, final double b,
                             final double c, final double t)
    {
        double s = d;
        double e = 0.0d;

        double pr = s * t;
        double sm = pr + a;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, a, sm));
        s = sm;

        pr = s * t;
        sm = pr + b;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, b, sm));
        s = sm;

        pr = s * t;
        sm = pr + c;
        e = Math.fma(e, t, Math.fma(s, t, -pr) + twoSumErr(pr, c, sm));

        return sm + e;
    }

    public static double discriminant(final double a, final double b, final double c) {
        final double a4 = 4.0d * a;    // exact: scaling by a power of two
        final double p = b * b;
        final double q = a4 * c;
        // p + dp == b * b and q + dq == a4 * c, both exactly
        final double dp = Math.fma(b, b, -p);
        final double dq = Math.fma(a4, c, -q);
        return (p - q) + (dp - dq);
    }

    public static int discriminantSign(final double d, final double a,
                                final double b, final double c)
    {
        // f'(t) = 3d*t^2 + 2a*t + b
        final double da = 3.0d * d;
        final double db = 2.0d * a;
        final double disc = discriminant(da, db, b);

        if (disc < 0.0d) {
            // f' has no real root: f is strictly monotonic, so it has a single root
            return 1;
        }

        // same cancellation-free form as quadraticRoots
        double sq = Math.sqrt(disc);
        if (db < 0.0d) {
            sq = -sq;
        }
        final double qd = (db + sq) / -2.0d;
        final double t1 = qd / da;
        final double t2 = (qd != 0.0d) ? (b / qd) : (-db / da);

        final double f1 = compHorner(d, a, b, c, t1);
        final double f2 = compHorner(d, a, b, c, t2);

        if ((f1 == 0.0d) || (f2 == 0.0d)) {
            // a critical point is a root of f: multiple root
            return 0;
        }
        // opposite signs: the curve crosses zero three times
        return ((f1 > 0.0d) == (f2 > 0.0d)) ? 1 : -1;
    }

    public static int quadraticRoots(final double a, final double b, final double c,
                              final double[] zeroes, final int off)
    {
        int ret = off;
        if (a != 0.0d) {
            double d = discriminant(a, b, c);
            if (d > 0.0d) {
                d = Math.sqrt(d);
                // For accuracy, calculate one root using:
                //     (-b +/- d) / 2a
                // and the other using:
                //     2c / (-b +/- d)
                // Choose the sign of the +/- so that b+d gets larger in magnitude
                if (b < 0.0d) {
                    d = -d;
                }
                final double q = (b + d) / -2.0d;
                // We already tested a for being 0 above
                zeroes[ret++] = q / a;
                if (q != 0.0d) {
                    zeroes[ret++] = c / q;
                }
            } else if (d == 0.0d) {
                zeroes[ret++] = -b / (2.0d * a);
            }
        } else if (b != 0.0d) {
            zeroes[ret++] = -c / b;
        }

        // Kahan's discriminant above is what matters, and it is left at that: no Newton
        // refinement follows. It keeps every root within 2 ulps however close the two
        // roots are, where the plain b*b - 4ac loses a digit per digit of cancellation
        // and reaches 1.2e6 ulps at a root gap of 1e-7.
        //
        // A compensated-Horner Newton step on top does make these roots correctly
        // rounded, but Marlin has no use for it. Over 200000 device-clipped curves the
        // three quadratics of findSubdivPoints -- dxRoots, dyRoots and infPoints --
        // place their subdivision points within 2.06e-12 px of the exact position
        // without it, against a tolerance of 1/256 px = 3.906e-3, and the step doubles
        // the cost of those three solves from 42 ns to 89. Nine orders of margin is not
        // worth paying for.
        //
        // Should it ever be wanted again, the step must use a COMPENSATED residual: with
        // a plain fma Horner residual the same step is a severe regression, up to 4.5e5
        // ulps at a root gap of 1e-6 against 1.4 ulps for no step at all, because f(t)
        // near a root is rounding noise and Newton divides it by an equally small f'.
        return ret - off;
    }

    public static int closedFormCandidates(final double d, final double a, final double b,
                                     final double c, final double[] pts, final int off)
    {
        if (d == 0.0d) {
            return quadraticRoots(a, b, c, pts, off);
        }
        // From Graphics Gems:
        // https://github.com/erich666/GraphicsGems/blob/master/gems/Roots3And4.c
        // (also from awt.geom.CubicCurve2D. But here we don't need as
        // much accuracy and we don't want to create arrays so we use
        // our own customized version).

        // normal form: x^3 + ax^2 + bx + c = 0
        final double an = a / d;
        final double bn = b / d;
        final double cn = c / d;

        //  substitute x = y - A/3 to eliminate quadratic term:
        //     x^3 +Px + Q = 0
        //
        // Since we actually need P/3 and Q/2 for all of the
        // calculations that follow, we will calculate
        // p = P/3
        // q = Q/2
        // instead and use those values for simplicity of the code.
        // p = (3b - a^2) / 9 and q = (2a^3 - 9ab + 27c) / 54, evaluated with the
        // rounding error of every product recovered by fma and every sum by
        // TwoSum. Both expressions cancel badly when the roots are close
        // together, and the sign and magnitude of D = q^2 + p^3 below decide
        // which branch is taken, so their error propagates into the branch
        // choice and not just into the returned values.
        final double sq_A = an * an;
        final double sq_A_err = Math.fma(an, an, -sq_A);
        final double cb_A = sq_A * an;
        final double cb_A_err = Math.fma(sq_A, an, -cb_A) + sq_A_err * an;
        final double ab = an * bn;
        final double ab_err = Math.fma(an, bn, -ab);

        final double b3 = 3.0d * bn;
        final double b3_err = Math.fma(3.0d, bn, -b3);
        final double ps = b3 - sq_A;
        final double p = (ps + ((b3_err - sq_A_err)
                                + twoSumErr(b3, -sq_A, ps))) / 9.0d;

        final double sub = an / 3.0d;

        final double t1 = 2.0d * cb_A;          // exact
        final double t1_err = 2.0d * cb_A_err;  // exact
        final double t2 = 9.0d * ab;
        final double t2_err = Math.fma(9.0d, ab, -t2) + 9.0d * ab_err;
        final double t3 = 27.0d * cn;
        final double t3_err = Math.fma(27.0d, cn, -t3);
        final double qs1 = t1 - t2;
        final double qs2 = qs1 + t3;
        final double q = (qs2 + (((t1_err - t2_err) + t3_err)
                                 + (twoSumErr(t1, -t2, qs1)
                                    + twoSumErr(qs1, t3, qs2)))) / 54.0d;

        // use Cardano's formula
        // p^3 and D = q^2 + p^3 are compensated as well: D vanishes exactly
        // when the cubic has a multiple root, so every digit lost here turns
        // into a misclassified branch.
        final double sq_p = p * p;
        final double sq_p_err = Math.fma(p, p, -sq_p);
        final double cb_p_hi = sq_p * p;
        final double cb_p_err = Math.fma(sq_p, p, -cb_p_hi) + sq_p_err * p;
        final double cb_p = cb_p_hi + cb_p_err;
        final double sq_q = q * q;
        final double sq_q_err = Math.fma(q, q, -sq_q);
        final double Ds = sq_q + cb_p_hi;
        final double D = Ds + ((sq_q_err + cb_p_err)
                               + twoSumErr(sq_q, cb_p_hi, Ds));

        int num;

        // The branch is chosen from discriminantSign() rather than from D above.
        // No tolerance is involved: the former test |D| <= 1e-9 was absolute while
        // D scales with the sixth power of the roots, so it reported a double root
        // for any cubic whose roots were merely small -- every cubic with all roots
        // below 0.1 returned two roots instead of three. A tolerance relative to
        // max(q^2, |p^3|) fails the other way round, merging roots that are distinct
        // but tiny beside a large third root. And the sign of the compensated D
        // itself is wrong for 9.5% of cubics once the coefficients span 20 decades,
        // which discriminantSign() gets exactly right. D is still used for its
        // magnitude in Cardano's branch, where only sqrt(D) is needed.
        final int sgn = discriminantSign(d, a, b, c);

        if (sgn == 0) {
            if (q == 0.0d) {
                /* one triple solution */
                pts[off    ] = (- sub);
                num = 1;
            } else {
                /* one single and one double solution */
                final double u = Math.cbrt(-q);
                pts[off    ] = Math.fma(2.0d, u, -sub);
                pts[off + 1] = (- u - sub);
                num = 2;
            }
        } else if ((sgn < 0) && (p < 0.0d)) {
            // see: http://en.wikipedia.org/wiki/Cubic_function#Trigonometric_.28and_hyperbolic.29_method
            // three real roots imply p < 0; the test guards the sqrt below against a
            // p that rounded to zero or above, in which case Cardano's branch is used
            final double arg = -q / Math.sqrt(-cb_p);
            // |arg| <= 1 holds mathematically here, clamped against rounding
            final double phi = (1.0d / 3.0d)
                    * Math.acos((arg < -1.0d) ? -1.0d : ((arg > 1.0d) ? 1.0d : arg));
            final double t = 2.0d * Math.sqrt(-p);

            // fma folds the final subtraction into the product: that shift
            // dominates the error of the trigonometric branch whenever a root
            // is much smaller than the mean of the three.
            pts[off    ] = Math.fma( t, Math.cos(phi), -sub);
            pts[off + 1] = Math.fma(-t, Math.cos(phi + (Math.PI / 3.0d)), -sub);
            pts[off + 2] = Math.fma(-t, Math.cos(phi - (Math.PI / 3.0d)), -sub);
            num = 3;
        } else {
            // sgn > 0 means a single real root; D is its magnitude, clamped in case
            // the compensated value disagrees in sign with discriminantSign()
            final double sqrt_D = Math.sqrt((D > 0.0d) ? D : 0.0d);
            // take the cube root of whichever of (sqrt_D -/+ q) does not cancel
            // and get the other one from u * v == -p: evaluating both cube roots
            // loses all significance in the smaller one when |p^3| << q^2.
            final double u = (q > 0.0d) ? -Math.cbrt(sqrt_D + q)
                                       :   Math.cbrt(sqrt_D - q);
            final double v = (u != 0.0d) ? -p / u : 0.0d;

            pts[off    ] = (u + v - sub);
            num = 1;
        }


        // One Newton step per root, against the polynomial this call was given, with
        // the residual from the compensated Horner scheme. It has to happen here and
        // not only after the two directions are pooled: refining s against the
        // reversed cubic is far better conditioned than refining 1/s against the
        // original one when s is large, and a near-double root needs this step as
        // well as the one in cubicRootsInAB to converge -- with only the later step
        // the correctly rounded share at a root gap of 1e-6 falls from 98% to 40%.
        for (int i = off, end = off + num; i < end; i++) {
            final double t = pts[i];
            final double f  = compHorner(d, a, b, c, t);
            final double fp = Math.fma(Math.fma(3.0d * d, t, 2.0d * a), t, b);

            if ((f != 0.0d) && (fp != 0.0d)) {
                final double nt = t - f / fp;
                // a multiple root gives fp ~ 0: keep the unrefined value then
                if (Double.isFinite(nt)) {
                    pts[i] = nt;
                }
            }
        }

        return num;
    }

    public static int criticalPoints(final double d, final double a, final double b,
                                      final double[] cp)
    {
        final double da = 3.0d * d;
        final double db = 2.0d * a;
        final double disc = discriminant(da, db, b);

        if (disc < 0.0d) {
            return 0;
        }
        double sq = Math.sqrt(disc);
        if (db < 0.0d) {
            sq = -sq;
        }
        final double qd = (db + sq) / -2.0d;
        final double t1 = qd / da;
        final double t2 = (qd != 0.0d) ? (b / qd) : (-db / da);

        cp[0] = Math.min(t1, t2);
        cp[1] = Math.max(t1, t2);
        return (disc == 0.0d) ? 1 : 2;
    }

    public static double solveBracket(final double d, final double a, final double b,
                                       final double c, double lo, double hi,
                                       final double flo, final double guess)
    {
        double t = ((guess > lo) && (guess < hi)) ? guess : 0.5d * (lo + hi);
        final boolean negLo = (flo < 0.0d);
        double best = t;
        double fbest = Double.POSITIVE_INFINITY;

        // The cap is a cost/accuracy knob, not a safety net: the smallest-|f| point is
        // tracked, so stopping early still returns the best double seen.
        //
        // Target: coefficients spanning up to 20 decades, and subdivision points accurate
        // to 1/512 px. Pixel accuracy stops binding at 16, where the position error is
        // already identically zero -- what binds is not losing a root.
        //
        // Over 1e6 polynomials of 20-decade coefficients, 312912 roots, the loss rate is
        // 0.471% at 28, 0.156% at 30, 0.025% at 32 and zero from 34 up. Device-scale
        // curves are easier: 1e6 of them, 1536662 roots, lose nothing from 28 up. The
        // value below is that 20-decade boundary of 34 plus six, and it is independently
        // the smallest cap that loses no root on any of the eight coefficient shapes
        // measured, including 40 decades and perpendiculardfddf with coordinates from
        // 1e-7 to 1e30, where 32 loses 9 roots apiece.
        //
        // Sample size decides this number, so do not re-tune it on a small one. A
        // 6066-root sample put the boundary at 26 and a 18922-root sample at 40; only at
        // 312912 roots does it settle, because the rate near the boundary is a few per
        // hundred thousand. The original closed form, for scale, loses 54.55% of the same
        // 312912 roots.
        for (int it = 0; it < 40; it++) {
            final double f = compHorner(d, a, b, c, t);
            final double af = Math.abs(f);

            if (af < fbest) {
                fbest = af;
                best = t;
            }
            if (f == 0.0d) {
                return t;
            }
            final double fp = Math.fma(Math.fma(3.0d * d, t, 2.0d * a), t, b);
            double nt = (fp != 0.0d) ? (t - f / fp) : Double.NaN;
            final boolean newtonOk = Double.isFinite(nt) && (nt > lo) && (nt < hi);

            if ((f < 0.0d) == negLo) {
                lo = t;
            } else {
                hi = t;
            }

            if (!newtonOk || (nt <= lo) || (nt >= hi)) {
                nt = 0.5d * (lo + hi);
                if ((nt <= lo) || (nt >= hi)) {
                    break;      // lo and hi are adjacent doubles: nothing left to halve
                }
            } else if (Math.abs(nt - t) <= REFINE_EPS * Math.abs(t)) {
                // Newton's own step bounds the error still to be removed, so a step this
                // small means the root is already resolved to REFINE_EPS. Take it and
                // stop: without this the loop runs on to the cap, 35.4 iterations per
                // root against 24.8, and a 20-decade sample costs 193 ns per solve
                // against 163, for roots that do not move. The bound is four orders
                // tighter than the 1.3e-7 in t that 1/512 px needs, and the loss counts
                // are identical with and without it at every cap measured.
                t = nt;
                final double fn = compHorner(d, a, b, c, t);

                if (Math.abs(fn) < fbest) {
                    best = t;
                }
                break;
            }
            if (nt == t) {
                break;
            }
            t = nt;
        }
        return best;
    }

    public static int cubicRootsInAB(final double d, final double a, final double b,
                              final double c, final double[] pts, final int off,
                              final double A, final double B)
    {
        if (d == 0.0d) {
            final int num = quadraticRoots(a, b, c, pts, off);
            return filterOutNotInAB(pts, off, num, A, B) - off;
        }

        // The closed form is fast and, on curves whose control points are of comparable
        // magnitude, exact: over 39456 device-scale perpendiculardfddf cubics and 29041
        // xPoints cubics it does not lose a single root. It fails on coefficients spanning
        // decades, and no cheap function of the coefficients tells the two apart -- its two
        // failure modes have opposite signatures, a large shift a/(3d) with a root
        // condition number of 1, versus a shift of exactly 1 with a condition number of
        // 1e11, and any threshold on the sum, the dynamic range, the shift or the
        // normalised size leaks about 1% of the failures.
        //
        // So it is verified rather than predicted. f is monotonic between its critical
        // points, so each piece whose endpoints differ in sign holds exactly one root; a
        // candidate is accepted only if it is the sole candidate inside such a piece AND a
        // Newton step moves it by no more than REFINE_EPS of its size, which bounds its
        // distance to the root. Anything else falls back to solveBracket, which cannot
        // lose a root at any scale. Acceptance therefore rests on demonstrated
        // convergence, and an unforeseen input class costs time instead of correctness.
        //
        // Measured: the fallback never fires on device-scale curves and the solve costs
        // 175 ns against 434 for bracketing everything; it fires on 1.9% of brackets for a
        // close root pair at gap 1e-6, 261 ns against 1150; and on 29% to 32% for
        // independent coefficients over 20 and 40 decades, where it costs about a quarter
        // more than bracketing directly. Roots lost and invented stay at zero throughout.
        final double[] cand = new double[4];
        final int nc = closedFormCandidates(d, a, b, c, cand, 0);

        final double[] cp = new double[2];
        final int ncp = criticalPoints(d, a, b, cp);

        final double[] bnd = new double[4];
        int nb = 0;
        bnd[nb++] = A;

        for (int i = 0; i < ncp; i++) {
            if ((cp[i] > A) && (cp[i] < B) && (cp[i] > bnd[nb - 1])) {
                bnd[nb++] = cp[i];
            }
        }
        bnd[nb++] = B;

        int num = 0;
        double flo = compHorner(d, a, b, c, bnd[0]);

        if (flo == 0.0d) {
            pts[off + num++] = bnd[0];          // f vanishes exactly at A
        }

        for (int i = 0; (i + 1) < nb; i++) {
            final double lo = bnd[i];
            final double hi = bnd[i + 1];
            final double fhi = compHorner(d, a, b, c, hi);

            if ((flo != 0.0d) && (fhi != 0.0d) && ((flo < 0.0d) != (fhi < 0.0d))) {
                // exactly one root in (lo, hi): take the closed form's if it stands up
                int found = 0;
                double t = Double.NaN;

                for (int j = 0; j < nc; j++) {
                    if ((cand[j] > lo) && (cand[j] < hi)) {
                        found++;
                        t = cand[j];
                    }
                }
                boolean accepted = false;

                if (found == 1) {
                    final double f = compHorner(d, a, b, c, t);

                    if (f == 0.0d) {
                        accepted = true;
                    } else {
                        final double fp = Math.fma(Math.fma(3.0d * d, t, 2.0d * a), t, b);
                        final double nt = (fp != 0.0d) ? (t - f / fp) : Double.NaN;

                        // the Newton step bounds the distance still to travel
                        if (Double.isFinite(nt)
                                && (Math.abs(nt - t) <= REFINE_EPS * Math.abs(t)))
                        {
                            if (Math.abs(compHorner(d, a, b, c, nt)) <= Math.abs(f)) {
                                t = nt;
                            }
                            accepted = true;
                        }
                    }
                }
                if (!accepted) {
                    t = solveBracket(d, a, b, c, lo, hi, flo,
                                     lo - flo * (hi - lo) / (fhi - flo));
                }
                if ((t >= A) && (t < B) && (num < 3)) {
                    pts[off + num++] = t;
                }
            } else if ((fhi == 0.0d) && (hi < B) && (num < 3)) {
                pts[off + num++] = hi;          // f vanishes exactly at a boundary
            }
            flo = fhi;
        }

        // A root of even multiplicity sits at a critical point and changes no sign, so the
        // scan above cannot see it. Only an EXACT zero counts here: a critical point where
        // f is merely small is a near-tangency, and the polynomial as given has either two
        // simple roots there -- already found above -- or none.
        for (int i = 0; (i < ncp) && (num < 3); i++) {
            final double tc = cp[i];

            if ((tc < A) || (tc >= B) || (compHorner(d, a, b, c, tc) != 0.0d)) {
                continue;
            }
            boolean dup = false;

            for (int j = 0; j < num; j++) {
                if (pts[off + j] == tc) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                pts[off + num++] = tc;
            }
        }
        return num;
    }

    public static int filterOutNotInAB(final double[] nums, final int off, final int len,
                                final double a, final double b)
    {
        int ret = off;
        for (int i = off, end = off + len; i < end; i++) {
            if (nums[i] >= a && nums[i] < b) {
                nums[ret++] = nums[i];
            }
        }
        return ret;
    }
}
