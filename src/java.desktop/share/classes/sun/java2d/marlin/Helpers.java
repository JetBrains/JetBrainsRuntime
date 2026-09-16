/*
 * Copyright (c) 2007, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.java2d.marlin;

import java.util.Arrays;
import sun.java2d.marlin.stats.Histogram;
import sun.java2d.marlin.stats.StatLong;

final class Helpers implements MarlinConst {

    /** relative Newton step below which solveBracket considers a root resolved */
    private final static double REFINE_EPS = 1e-11;

    private final static double T_ERR = 1e-4;
    private final static double T_A = T_ERR;
    private final static double T_B = 1.0 - T_ERR;

    private static final double EPS = 1e-9d;

    private Helpers() {
        throw new Error("This is a non instantiable class");
    }

    /** use lower precision like former Pisces and Marlin (float-precision) */
    static double ulp(final double value) { return Math.ulp((float)value); }

    static boolean within(final double x, final double y) {
        return within(x, y, EPS);
    }

    static boolean within(final double x, final double y, final double err) {
        return withinD(y - x, err);
    }

    static boolean withinD(final double d, final double err) {
        return (d <= err && d >= -err);
    }

    static boolean withinD(final double dx, final double dy, final double err)
    {
        assert err > 0 : "";
        // compare taxicab distance. ERR will always be small, so using
        // true distance won't give much benefit
        return (withinD(dx, err) && // we want to avoid calling Math.abs
                withinD(dy, err));  // this is just as good.
    }

    static boolean isPointCurve(final double[] curve, final int type) {
        return isPointCurve(curve, type, EPS);
    }

    static boolean isPointCurve(final double[] curve, final int type, final double err) {
        for (int i = 2; i < type; i++) {
            if (!within(curve[i], curve[i - 2], err)) {
                return false;
            }
        }
        return true;
    }

    static double evalCubic(final double a, final double b,
                            final double c, final double d,
                            final double t)
    {
        return t * (t * (t * a + b) + c) + d;
    }

    static double evalQuad(final double a, final double b,
                           final double c, final double t)
    {
        return t * (t * a + b) + c;
    }

    /**
     * Exact rounding error of a sum: returns e such that (x + y) == s + e
     * exactly, where s is the rounded sum (x + y). Knuth's TwoSum.
     */
    static double twoSumErr(final double x, final double y, final double s) {
        final double bv = s - x;
        return (x - (s - bv)) + (y - bv);
    }

    /**
     * f(t) for d*t^3 + a*t^2 + b*t + c by a COMPENSATED Horner scheme: the
     * rounding error of every product (twoProduct, through fma) and of every sum
     * (twoSum) is accumulated in e and folded back at the end. Near a root the
     * plain evaluation cancels down to rounding noise, and that noise is exactly
     * what a Newton step would divide by f', so recovering it is what lets a
     * single step land on the correctly rounded root.
     */
    static double compHorner(final double d, final double a, final double b,
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

    /**
     * Discriminant (b^2 - 4ac) using Kahan's fma-based algorithm: the rounding
     * error of both products is recovered exactly by fma, so the result stays
     * accurate to within 2 ulps even when the two terms cancel, ie. when the
     * quadratic has nearly equal roots. Evaluating it as (b * b - 4 * a * c)
     * instead loses one digit per digit of cancellation, and the roots inherit
     * the whole error through sqrt().
     */
    static double discriminant(final double a, final double b, final double c) {
        final double a4 = 4.0d * a;    // exact: scaling by a power of two
        final double p = b * b;
        final double q = a4 * c;
        // p + dp == b * b and q + dq == a4 * c, both exactly
        final double dp = Math.fma(b, b, -p);
        final double dq = Math.fma(a4, c, -q);
        return (p - q) + (dp - dq);
    }

    static int quadraticRoots(final double a, final double b, final double c,
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

    /**
     * Sign of the discriminant D = q^2 + p^3 of d*t^3 + a*t^2 + b*t + c: -1 when
     * the cubic has three distinct real roots, +1 when it has a single one, and 0
     * for a multiple root.
     *
     * D is deliberately never formed. Evaluating q^2 + p^3 loses the sign outright
     * in the hard cases -- the two terms reach 1e60 and cancel down to 1e20 -- and
     * this sign selects the branch below, so getting it wrong means a root is never
     * returned at all rather than merely returned inaccurately. Instead, f has
     * three distinct real roots exactly when it takes opposite signs at its two
     * critical points:
     *
     *     sign(D) == sign(f(t1) * f(t2)),   f'(t1) = f'(t2) = 0
     *
     * f' is solved with the same Kahan discriminant as quadraticRoots, and f is
     * evaluated by compensated Horner. Because f'(t1) = 0, an error e in t1 moves
     * f(t1) only by f''(t1)*e^2/2, so the critical points need very little accuracy
     * for the sign comparison to hold; the compensated evaluation of f is what
     * matters, since f(t1) is itself nearly a cancellation when the roots are close.
     *
     * Measured against a 120-digit evaluation this is exact over 60000 random cubics
     * at each of 6, 12, 20 and 30 decades of coefficient range, and on cubics with a
     * near-double root down to a gap of 1e-12 as well as on exact double roots --
     * where forming D instead gets 15% to 26% of the signs wrong.
     */
    static int discriminantSign(final double d, final double a,
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

    /**
     * Candidate roots of d*t^3 + a*t^2 + b*t + c from the closed form: Cardano's and
     * the trigonometric formulas on the depressed cubic, refined by one
     * compensated-Horner Newton step. Unfiltered, at most three written at off.
     *
     * These are CANDIDATES only. cubicRootsInAB verifies each against the bracket it
     * should fall in and discards it otherwise, so an error here costs a fallback to
     * solveBracket and never a wrong root. That is what makes keeping the closed form
     * affordable: it need only be right often enough, not always.
     */
    private static int closedFormCandidates(final double d, final double a, final double b,
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

    /**
     * Critical points of f(t) = d*t^3 + a*t^2 + b*t + c, ie the roots of
     * f'(t) = 3d*t^2 + 2a*t + b, written ascending into cp[0..1]. Returns how many
     * are real: 0 when f is strictly monotonic, 1 for a horizontal inflection, 2
     * otherwise. Uses the same Kahan discriminant and cancellation-free form as
     * quadraticRoots.
     */
    private static int criticalPoints(final double d, final double a, final double b,
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

    /**
     * The single root of f in (lo, hi), where f(lo) and f(hi) have opposite signs.
     * Newton safeguarded by the bracket: the step is taken when it stays inside,
     * and replaced by the midpoint when it does not, so convergence is guaranteed
     * however bad the starting guess is. The residual comes from the compensated
     * Horner scheme, and the point with the smallest |f| seen is returned -- for a
     * simple root that is the correctly rounded double.
     *
     * The Newton step is computed BEFORE the bracket is narrowed; narrowing first
     * rejects a step back towards the root as out of bounds and forces a midpoint
     * jump instead, which costs all the accuracy the guess had.
     */
    private static double solveBracket(final double d, final double a, final double b,
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

    // find the roots of g(t) = d*t^3 + a*t^2 + b*t + c in [A,B)
    static int cubicRootsInAB(final double d, final double a, final double b,
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

    // returns the index 1 past the last valid element remaining after filtering
    static int filterOutNotInAB(final double[] nums, final int off, final int len,
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

    static double fastLineLen(final double x0, final double y0,
                              final double x1, final double y1)
    {
        final double dx = x1 - x0;
        final double dy = y1 - y0;

        // use manhattan norm:
        return Math.abs(dx) + Math.abs(dy);
    }

    static double linelen(final double x0, final double y0,
                          final double x1, final double y1)
    {
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        return Math.sqrt(dx * dx + dy * dy);
    }

    static double fastQuadLen(final double x0, final double y0,
                              final double x1, final double y1,
                              final double x2, final double y2)
    {
        final double dx1 = x1 - x0;
        final double dx2 = x2 - x1;
        final double dy1 = y1 - y0;
        final double dy2 = y2 - y1;

        // use manhattan norm:
        return Math.abs(dx1) + Math.abs(dx2)
             + Math.abs(dy1) + Math.abs(dy2);
    }

    static double quadlen(final double x0, final double y0,
                          final double x1, final double y1,
                          final double x2, final double y2)
    {
        return (linelen(x0, y0, x1, y1)
                + linelen(x1, y1, x2, y2)
                + linelen(x0, y0, x2, y2)) / 2.0d;
    }

    static double fastCurvelen(final double x0, final double y0,
                               final double x1, final double y1,
                               final double x2, final double y2,
                               final double x3, final double y3)
    {
        final double dx1 = x1 - x0;
        final double dx2 = x2 - x1;
        final double dx3 = x3 - x2;
        final double dy1 = y1 - y0;
        final double dy2 = y2 - y1;
        final double dy3 = y3 - y2;

        // use manhattan norm:
        return Math.abs(dx1) + Math.abs(dx2) + Math.abs(dx3)
             + Math.abs(dy1) + Math.abs(dy2) + Math.abs(dy3);
    }

    static double curvelen(final double x0, final double y0,
                           final double x1, final double y1,
                           final double x2, final double y2,
                           final double x3, final double y3)
    {
        return (linelen(x0, y0, x1, y1)
              + linelen(x1, y1, x2, y2)
              + linelen(x2, y2, x3, y3)
              + linelen(x0, y0, x3, y3)) / 2.0d;
    }

    // finds values of t where the curve in pts should be subdivided in order
    // to get good offset curves a distance of w away from the middle curve.
    // Stores the points in ts, and returns how many of them there were.
    static int findSubdivPoints(final Curve c, final double[] pts,
                                final double[] ts, final int type,
                                final double w2)
    {
        final double x12 = pts[2] - pts[0];
        final double y12 = pts[3] - pts[1];
        // if the curve is already parallel to either axis we gain nothing
        // from rotating it.
        if ((y12 != 0.0d) && (x12 != 0.0d)) {
            // we rotate it so that the first vector in the control polygon is
            // parallel to the x-axis. This will ensure that rotated quarter
            // circles won't be subdivided.
            final double hypot = Math.sqrt(x12 * x12 + y12 * y12);
            final double cos = x12 / hypot;
            final double sin = y12 / hypot;
            final double x1 = cos * pts[0] + sin * pts[1];
            final double y1 = cos * pts[1] - sin * pts[0];
            final double x2 = cos * pts[2] + sin * pts[3];
            final double y2 = cos * pts[3] - sin * pts[2];
            final double x3 = cos * pts[4] + sin * pts[5];
            final double y3 = cos * pts[5] - sin * pts[4];

            switch(type) {
            case 8:
                final double x4 = cos * pts[6] + sin * pts[7];
                final double y4 = cos * pts[7] - sin * pts[6];
                c.set(x1, y1, x2, y2, x3, y3, x4, y4);
                break;
            case 6:
                c.set(x1, y1, x2, y2, x3, y3);
                break;
            default:
            }
        } else {
            c.set(pts, type);
        }

        int ret = 0;
        // we subdivide at values of t such that the remaining rotated
        // curves are monotonic in x and y.
        ret += c.dxRoots(ts, ret);
        ret += c.dyRoots(ts, ret);

        // subdivide at inflection points.
        if (type == 8) {
            // quadratic curves can't have inflection points
            ret += c.infPoints(ts, ret);
        }

        // now we must subdivide at points where one of the offset curves will have
        // a cusp. This happens at ts where the radius of curvature is equal to w.
        ret += c.rootsOfROCMinusW(ts, ret, w2, T_A, T_B);

        ret = filterOutNotInAB(ts, 0, ret, T_A, T_B);
        isort(ts, 0, ret);
        return ret;
    }

    // finds values of t where the curve in pts should be subdivided in order
    // to get intersections with the given clip rectangle.
    // Stores the points in ts, and returns how many of them there were.
    static int findClipPoints(final Curve curve, final double[] pts,
                              final double[] ts, final int type,
                              final int outCodeOR,
                              final double[] clipRect)
    {
        curve.set(pts, type);

        // clip rectangle (ymin, ymax, xmin, xmax)
        int ret = 0;

        if ((outCodeOR & OUTCODE_LEFT) != 0) {
            ret += curve.xPoints(ts, ret, clipRect[2]);
        }
        if ((outCodeOR & OUTCODE_RIGHT) != 0) {
            ret += curve.xPoints(ts, ret, clipRect[3]);
        }
        if ((outCodeOR & OUTCODE_TOP) != 0) {
            ret += curve.yPoints(ts, ret, clipRect[0]);
        }
        if ((outCodeOR & OUTCODE_BOTTOM) != 0) {
            ret += curve.yPoints(ts, ret, clipRect[1]);
        }
        isort(ts, 0, ret);
        return ret;
    }

    static void subdivide(final double[] src,
                          final double[] left, final double[] right,
                          final int type)
    {
        switch(type) {
        case 8:
            subdivideCubic(src, left, right);
            return;
        case 6:
            subdivideQuad(src, left, right);
            return;
        default:
            throw new InternalError("Unsupported curve type");
        }
    }

    static void isort(final double[] a, final int off, final int len) {
        for (int i = off + 1, j; i < len; i++) {
            final double ai = a[i];
            j = i - 1;
            for (; j >= off && a[j] > ai; j--) {
                a[j + 1] = a[j];
            }
            a[j + 1] = ai;
        }
    }

    // Most of these are copied from classes in java.awt.geom because we need
    // both single and double precision variants of these functions, and Line2D,
    // CubicCurve2D, QuadCurve2D don't provide them.
    /**
     * Subdivides the cubic curve specified by the coordinates
     * stored in the <code>src</code> array at indices <code>srcoff</code>
     * through (<code>srcoff</code>&nbsp;+&nbsp;7) and stores the
     * resulting two subdivided curves into the two result arrays at the
     * corresponding indices.
     * Either or both of the <code>left</code> and <code>right</code>
     * arrays may be <code>null</code> or a reference to the same array
     * as the <code>src</code> array.
     * Note that the last point in the first subdivided curve is the
     * same as the first point in the second subdivided curve. Thus,
     * it is possible to pass the same array for <code>left</code>
     * and <code>right</code> and to use offsets, such as <code>rightoff</code>
     * equals (<code>leftoff</code> + 6), in order
     * to avoid allocating extra storage for this common point.
     * @param src the array holding the coordinates for the source curve
     * @param left the array for storing the coordinates for the first
     * half of the subdivided curve
     * @param right the array for storing the coordinates for the second
     * half of the subdivided curve
     * @since 1.7
     */
    static void subdivideCubic(final double[] src,
                               final double[] left,
                               final double[] right)
    {
        double  x1 = src[0];
        double  y1 = src[1];
        double cx1 = src[2];
        double cy1 = src[3];
        double cx2 = src[4];
        double cy2 = src[5];
        double  x2 = src[6];
        double  y2 = src[7];

        left[0]  = x1;
        left[1]  = y1;

        right[6] = x2;
        right[7] = y2;

        x1 = (x1 + cx1) / 2.0d;
        y1 = (y1 + cy1) / 2.0d;
        x2 = (x2 + cx2) / 2.0d;
        y2 = (y2 + cy2) / 2.0d;

        double cx = (cx1 + cx2) / 2.0d;
        double cy = (cy1 + cy2) / 2.0d;

        cx1 = (x1 + cx) / 2.0d;
        cy1 = (y1 + cy) / 2.0d;
        cx2 = (x2 + cx) / 2.0d;
        cy2 = (y2 + cy) / 2.0d;
        cx  = (cx1 + cx2) / 2.0d;
        cy  = (cy1 + cy2) / 2.0d;

        left[2] = x1;
        left[3] = y1;
        left[4] = cx1;
        left[5] = cy1;
        left[6] = cx;
        left[7] = cy;

        right[0] = cx;
        right[1] = cy;
        right[2] = cx2;
        right[3] = cy2;
        right[4] = x2;
        right[5] = y2;
    }

    static void subdivideCubicAt(final double t,
                                 final double[] src, final int offS,
                                 final double[] pts, final int offL, final int offR)
    {
        double  x1 = src[offS    ];
        double  y1 = src[offS + 1];
        double cx1 = src[offS + 2];
        double cy1 = src[offS + 3];
        double cx2 = src[offS + 4];
        double cy2 = src[offS + 5];
        double  x2 = src[offS + 6];
        double  y2 = src[offS + 7];

        pts[offL    ] = x1;
        pts[offL + 1] = y1;

        pts[offR + 6] = x2;
        pts[offR + 7] = y2;

        x1 =  x1 + t * (cx1 - x1);
        y1 =  y1 + t * (cy1 - y1);
        x2 = cx2 + t * (x2 - cx2);
        y2 = cy2 + t * (y2 - cy2);

        double cx = cx1 + t * (cx2 - cx1);
        double cy = cy1 + t * (cy2 - cy1);

        cx1 =  x1 + t * (cx - x1);
        cy1 =  y1 + t * (cy - y1);
        cx2 =  cx + t * (x2 - cx);
        cy2 =  cy + t * (y2 - cy);
        cx  = cx1 + t * (cx2 - cx1);
        cy  = cy1 + t * (cy2 - cy1);

        pts[offL + 2] = x1;
        pts[offL + 3] = y1;
        pts[offL + 4] = cx1;
        pts[offL + 5] = cy1;
        pts[offL + 6] = cx;
        pts[offL + 7] = cy;

        pts[offR    ] = cx;
        pts[offR + 1] = cy;
        pts[offR + 2] = cx2;
        pts[offR + 3] = cy2;
        pts[offR + 4] = x2;
        pts[offR + 5] = y2;
    }

    static void subdivideQuad(final double[] src,
                              final double[] left,
                              final double[] right)
    {
        double x1 = src[0];
        double y1 = src[1];
        double cx = src[2];
        double cy = src[3];
        double x2 = src[4];
        double y2 = src[5];

        left[0]  = x1;
        left[1]  = y1;

        right[4] = x2;
        right[5] = y2;

        x1 = (x1 + cx) / 2.0d;
        y1 = (y1 + cy) / 2.0d;
        x2 = (x2 + cx) / 2.0d;
        y2 = (y2 + cy) / 2.0d;
        cx = (x1 + x2) / 2.0d;
        cy = (y1 + y2) / 2.0d;

        left[2] = x1;
        left[3] = y1;
        left[4] = cx;
        left[5] = cy;

        right[0] = cx;
        right[1] = cy;
        right[2] = x2;
        right[3] = y2;
    }

    static void subdivideQuadAt(final double t,
                                final double[] src, final int offS,
                                final double[] pts, final int offL, final int offR)
    {
        double x1 = src[offS    ];
        double y1 = src[offS + 1];
        double cx = src[offS + 2];
        double cy = src[offS + 3];
        double x2 = src[offS + 4];
        double y2 = src[offS + 5];

        pts[offL    ] = x1;
        pts[offL + 1] = y1;

        pts[offR + 4] = x2;
        pts[offR + 5] = y2;

        x1 = x1 + t * (cx - x1);
        y1 = y1 + t * (cy - y1);
        x2 = cx + t * (x2 - cx);
        y2 = cy + t * (y2 - cy);
        cx = x1 + t * (x2 - x1);
        cy = y1 + t * (y2 - y1);

        pts[offL + 2] = x1;
        pts[offL + 3] = y1;
        pts[offL + 4] = cx;
        pts[offL + 5] = cy;

        pts[offR    ] = cx;
        pts[offR + 1] = cy;
        pts[offR + 2] = x2;
        pts[offR + 3] = y2;
    }

    static void subdivideLineAt(final double t,
                                final double[] src, final int offS,
                                final double[] pts, final int offL, final int offR)
    {
        double x1 = src[offS    ];
        double y1 = src[offS + 1];
        double x2 = src[offS + 2];
        double y2 = src[offS + 3];

        pts[offL    ] = x1;
        pts[offL + 1] = y1;

        pts[offR + 2] = x2;
        pts[offR + 3] = y2;

        x1 = x1 + t * (x2 - x1);
        y1 = y1 + t * (y2 - y1);

        pts[offL + 2] = x1;
        pts[offL + 3] = y1;

        pts[offR    ] = x1;
        pts[offR + 1] = y1;
    }

    static void subdivideAt(final double t,
                            final double[] src, final int offS,
                            final double[] pts, final int offL, final int type)
    {
        // if instead of switch (perf + most probable cases first)
        if (type == 8) {
            subdivideCubicAt(t, src, offS, pts, offL, offL + type);
        } else if (type == 4) {
            subdivideLineAt(t, src, offS, pts, offL, offL + type);
        } else {
            subdivideQuadAt(t, src, offS, pts, offL, offL + type);
        }
    }

    // From sun.java2d.loops.GeneralRenderer:

    static int outcode(final double x, final double y,
                       final double[] clipRect)
    {
        int code;
        if (y < clipRect[0]) {
            code = OUTCODE_TOP;
        } else if (y >= clipRect[1]) {
            code = OUTCODE_BOTTOM;
        } else {
            code = 0;
        }
        if (x < clipRect[2]) {
            code |= OUTCODE_LEFT;
        } else if (x >= clipRect[3]) {
            code |= OUTCODE_RIGHT;
        }
        return code;
    }

    // a stack of polynomial curves where each curve shares endpoints with
    // adjacent ones.
    static final class PolyStack {
        private static final byte TYPE_LINETO  = (byte) 0;
        private static final byte TYPE_QUADTO  = (byte) 1;
        private static final byte TYPE_CUBICTO = (byte) 2;

        // curves capacity = edges count (8192) = edges x 2 (coords)
        private static final int INITIAL_CURVES_COUNT = INITIAL_EDGES_COUNT << 1;

        // types capacity = edges count (4096)
        private static final int INITIAL_TYPES_COUNT = INITIAL_EDGES_COUNT;

        double[] curves;
        int end;
        byte[] curveTypes;
        int numCurves;

        // curves ref (dirty)
        final ArrayCacheDouble.Reference curves_ref;
        // curveTypes ref (dirty)
        final ArrayCacheByte.Reference curveTypes_ref;

        // used marks (stats only)
        int curveTypesUseMark;
        int curvesUseMark;

        private final StatLong stat_polystack_types;
        private final StatLong stat_polystack_curves;
        private final Histogram hist_polystack_curves;
        private final StatLong stat_array_polystack_curves;
        private final StatLong stat_array_polystack_curveTypes;

        PolyStack(final RendererContext rdrCtx) {
            this(rdrCtx, null, null, null, null, null);
        }

        PolyStack(final RendererContext rdrCtx,
                  final StatLong stat_polystack_types,
                  final StatLong stat_polystack_curves,
                  final Histogram hist_polystack_curves,
                  final StatLong stat_array_polystack_curves,
                  final StatLong stat_array_polystack_curveTypes)
        {
            curves_ref = rdrCtx.newDirtyDoubleArrayRef(INITIAL_CURVES_COUNT); // 32K
            curves     = curves_ref.initial;

            curveTypes_ref = rdrCtx.newDirtyByteArrayRef(INITIAL_TYPES_COUNT); // 4K
            curveTypes     = curveTypes_ref.initial;
            numCurves = 0;
            end = 0;

            if (DO_STATS) {
                curveTypesUseMark = 0;
                curvesUseMark = 0;
            }
            this.stat_polystack_types = stat_polystack_types;
            this.stat_polystack_curves = stat_polystack_curves;
            this.hist_polystack_curves = hist_polystack_curves;
            this.stat_array_polystack_curves = stat_array_polystack_curves;
            this.stat_array_polystack_curveTypes = stat_array_polystack_curveTypes;
        }

        /**
         * Disposes this PolyStack:
         * clean up before reusing this instance
         */
        void dispose() {
            end       = 0;
            numCurves = 0;

            if (DO_STATS) {
                stat_polystack_types.add(curveTypesUseMark);
                stat_polystack_curves.add(curvesUseMark);
                hist_polystack_curves.add(curvesUseMark);

                // reset marks
                curveTypesUseMark = 0;
                curvesUseMark = 0;
            }

            // Return arrays:
            // curves and curveTypes are kept dirty
            if (curves_ref.doCleanRef(curves)) {
                curves = curves_ref.putArray(curves);
            }
            if (curveTypes_ref.doCleanRef(curveTypes)) {
                curveTypes = curveTypes_ref.putArray(curveTypes);
            }
        }

        private void ensureSpace(final int n) {
            // use substraction to avoid integer overflow:
            if (curves.length - end < n) {
                if (DO_STATS) {
                    stat_array_polystack_curves.add(end + n);
                }
                curves = curves_ref.widenArray(curves, end, end + n);
            }
            if (curveTypes.length <= numCurves) {
                if (DO_STATS) {
                    stat_array_polystack_curveTypes.add(numCurves + 1);
                }
                curveTypes = curveTypes_ref.widenArray(curveTypes,
                                                       numCurves,
                                                       numCurves + 1);
            }
        }

        void pushCubic(double x0, double y0,
                       double x1, double y1,
                       double x2, double y2)
        {
            ensureSpace(6);
            curveTypes[numCurves++] = TYPE_CUBICTO;
            // we reverse the coordinate order to make popping easier
            final double[] _curves = curves;
            int e = end;
            _curves[e++] = x2;    _curves[e++] = y2;
            _curves[e++] = x1;    _curves[e++] = y1;
            _curves[e++] = x0;    _curves[e++] = y0;
            end = e;
        }

        void pushQuad(double x0, double y0,
                      double x1, double y1)
        {
            ensureSpace(4);
            curveTypes[numCurves++] = TYPE_QUADTO;
            final double[] _curves = curves;
            int e = end;
            _curves[e++] = x1;    _curves[e++] = y1;
            _curves[e++] = x0;    _curves[e++] = y0;
            end = e;
        }

        void pushLine(double x, double y) {
            ensureSpace(2);
            curveTypes[numCurves++] = TYPE_LINETO;
            curves[end++] = x;    curves[end++] = y;
        }

        void pullAll(final DPathConsumer2D io) {
            final int nc = numCurves;
            if (nc == 0) {
                return;
            }
            if (DO_STATS) {
                // update used marks:
                if (numCurves > curveTypesUseMark) {
                    curveTypesUseMark = numCurves;
                }
                if (end > curvesUseMark) {
                    curvesUseMark = end;
                }
            }
            final byte[]  _curveTypes = curveTypes;
            final double[] _curves = curves;
            int e = 0;

            for (int i = 0; i < nc; i++) {
                switch(_curveTypes[i]) {
                case TYPE_LINETO:
                    io.lineTo(_curves[e], _curves[e+1]);
                    e += 2;
                    continue;
                case TYPE_CUBICTO:
                    io.curveTo(_curves[e],   _curves[e+1],
                               _curves[e+2], _curves[e+3],
                               _curves[e+4], _curves[e+5]);
                    e += 6;
                    continue;
                case TYPE_QUADTO:
                    io.quadTo(_curves[e],   _curves[e+1],
                              _curves[e+2], _curves[e+3]);
                    e += 4;
                    continue;
                default:
                }
            }
            numCurves = 0;
            end = 0;
        }

        void popAll(final DPathConsumer2D io) {
            int nc = numCurves;
            if (nc == 0) {
                return;
            }
            if (DO_STATS) {
                // update used marks:
                if (numCurves > curveTypesUseMark) {
                    curveTypesUseMark = numCurves;
                }
                if (end > curvesUseMark) {
                    curvesUseMark = end;
                }
            }
            final byte[]  _curveTypes = curveTypes;
            final double[] _curves = curves;
            int e  = end;

            while (nc != 0) {
                switch(_curveTypes[--nc]) {
                case TYPE_LINETO:
                    e -= 2;
                    io.lineTo(_curves[e], _curves[e+1]);
                    continue;
                case TYPE_CUBICTO:
                    e -= 6;
                    io.curveTo(_curves[e],   _curves[e+1],
                               _curves[e+2], _curves[e+3],
                               _curves[e+4], _curves[e+5]);
                    continue;
                case TYPE_QUADTO:
                    e -= 4;
                    io.quadTo(_curves[e],   _curves[e+1],
                              _curves[e+2], _curves[e+3]);
                    continue;
                default:
                }
            }
            numCurves = 0;
            end = 0;
        }

        @Override
        public String toString() {
            StringBuilder ret = new StringBuilder();
            int nc = numCurves;
            int last = end;
            int len;
            while (nc != 0) {
                switch(curveTypes[--nc]) {
                case TYPE_LINETO:
                    len = 2;
                    ret.append("line: ");
                    break;
                case TYPE_QUADTO:
                    len = 4;
                    ret.append("quad: ");
                    break;
                case TYPE_CUBICTO:
                    len = 6;
                    ret.append("cubic: ");
                    break;
                default:
                    len = 0;
                }
                last -= len;
                ret.append(Arrays.toString(Arrays.copyOfRange(curves, last, last + len))).append("\n");
            }
            return ret.toString();
        }
    }

    // a stack of integer indices
    static final class IndexStack {

        // integer capacity = edges count / 4 ~ 1024
        private static final int INITIAL_COUNT = INITIAL_EDGES_COUNT >> 2;

        private int end;
        private int[] indices;

        // indices ref (dirty)
        private final ArrayCacheInt.Reference indices_ref;

        // used marks (stats only)
        private int indicesUseMark;

        private final StatLong stat_idxstack_indices;
        private final Histogram hist_idxstack_indices;
        private final StatLong stat_array_idxstack_indices;

        IndexStack(final RendererContext rdrCtx) {
            this(rdrCtx, null, null, null);
        }

        IndexStack(final RendererContext rdrCtx,
                   final StatLong stat_idxstack_indices,
                   final Histogram hist_idxstack_indices,
                   final StatLong stat_array_idxstack_indices)
        {
            indices_ref = rdrCtx.newDirtyIntArrayRef(INITIAL_COUNT); // 4K
            indices     = indices_ref.initial;
            end = 0;

            if (DO_STATS) {
                indicesUseMark = 0;
            }
            this.stat_idxstack_indices = stat_idxstack_indices;
            this.hist_idxstack_indices = hist_idxstack_indices;
            this.stat_array_idxstack_indices = stat_array_idxstack_indices;
        }

        /**
         * Disposes this PolyStack:
         * clean up before reusing this instance
         */
        void dispose() {
            end = 0;

            if (DO_STATS) {
                stat_idxstack_indices.add(indicesUseMark);
                hist_idxstack_indices.add(indicesUseMark);

                // reset marks
                indicesUseMark = 0;
            }

            // Return arrays:
            // indices is kept dirty
            if (indices_ref.doCleanRef(indices)) {
                indices = indices_ref.putArray(indices);
            }
        }

        boolean isEmpty() {
            return (end == 0);
        }

        void reset() {
            end = 0;
        }

        void push(final int v) {
            // remove redundant values (reverse order):
            int[] _values = indices;
            final int nc = end;
            if (nc != 0) {
                if (_values[nc - 1] == v) {
                    // remove both duplicated values:
                    end--;
                    return;
                }
            }
            if (_values.length <= nc) {
                if (DO_STATS) {
                    stat_array_idxstack_indices.add(nc + 1);
                }
                indices = _values = indices_ref.widenArray(_values, nc, nc + 1);
            }
            _values[end++] = v;

            if (DO_STATS) {
                // update used marks:
                if (end > indicesUseMark) {
                    indicesUseMark = end;
                }
            }
        }

        void pullAll(final double[] points, final DPathConsumer2D io,
                     final boolean moveFirst)
        {
            final int nc = end;
            if (nc == 0) {
                return;
            }
            final int[] _values = indices;

            int i = 0;

            if (moveFirst) {
                int j = _values[i] << 1;
                io.moveTo(points[j], points[j + 1]);
                i++;
            }

            for (int j; i < nc; i++) {
                j = _values[i] << 1;
                io.lineTo(points[j], points[j + 1]);
            }
            end = 0;
        }
    }
}
