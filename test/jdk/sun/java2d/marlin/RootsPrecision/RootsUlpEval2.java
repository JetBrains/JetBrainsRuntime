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
 * Precision of sun.java2d.marlin.Helpers root solvers RESTRICTED TO THE INTERVAL ACTUALLY USED BY MARLIN:
 *   t in [A, B) with A = eps, B = 1 - eps, eps ~ 1e-6
 * (Helpers.findSubdivPoints filters every root through filterOutNotInAB(ts,0,ret,T_A,T_B);
 *  cubicRootsInAB takes [A,B) directly.)
 *
 * Solver code is copied verbatim from Helpers.java. Reference: all real roots of the EXACT double
 * coefficients found independently (double bracketing + BigDecimal Newton at 60 digits), cross-checked
 * against the exact discriminant; only reference roots inside [A,B) are expected.
 */
public class RootsUlpEval2 {

    // ---------------------------------------------------------------- Helpers.java (verbatim)
    private static final double EPS = 1e-9d;
    static boolean within(final double x, final double y) { return within(x, y, EPS); }
    static boolean within(final double x, final double y, final double err) { return withinD(y - x, err); }
    static boolean withinD(final double d, final double err) { return (d <= err && d >= -err); }

    static int quadraticRoots(final double a, final double b, final double c,
                              final double[] zeroes, final int off) {
        int ret = off;
        if (a != 0.0d) {
            double d = b * b - 4.0d * a * c;
            if (d > 0.0d) {
                d = Math.sqrt(d);
                if (b < 0.0d) { d = -d; }
                final double q = (b + d) / -2.0d;
                zeroes[ret++] = q / a;
                if (q != 0.0d) { zeroes[ret++] = c / q; }
            } else if (d == 0.0d) {
                zeroes[ret++] = -b / (2.0d * a);
            }
        } else if (b != 0.0d) {
            zeroes[ret++] = -c / b;
        }
        return ret - off;
    }

    static int cubicRootsInAB(final double d, double a, double b, double c,
                              final double[] pts, final int off, final double A, final double B) {
        if (d == 0.0d) {
            final int num = quadraticRoots(a, b, c, pts, off);
            return filterOutNotInAB(pts, off, num, A, B) - off;
        }
        a /= d; b /= d; c /= d;
        final double sq_A = a * a;
        final double p = (1.0d / 3.0d) * ((-1.0d / 3.0d) * sq_A + b);
        final double sub = (1.0d / 3.0d) * a;
        final double q = (1.0d / 2.0d) * ((2.0d / 27.0d) * a * sq_A - sub * b + c);
        final double cb_p = p * p * p;
        final double D = q * q + cb_p;
        int num;
        if (within(D, 0.0d)) {
            if (within(q, 0.0d)) {
                pts[off] = (-sub); num = 1;
            } else {
                final double u = Math.cbrt(-q);
                pts[off] = (2.0d * u - sub); pts[off + 1] = (-u - sub); num = 2;
            }
        } else if (D < 0.0d) {
            final double phi = (1.0d / 3.0d) * Math.acos(-q / Math.sqrt(-cb_p));
            final double t = 2.0d * Math.sqrt(-p);
            pts[off] = (t * Math.cos(phi) - sub);
            pts[off + 1] = (-t * Math.cos(phi + (Math.PI / 3.0d)) - sub);
            pts[off + 2] = (-t * Math.cos(phi - (Math.PI / 3.0d)) - sub);
            num = 3;
        } else {
            final double sqrt_D = Math.sqrt(D);
            final double u = Math.cbrt(sqrt_D - q);
            final double v = -Math.cbrt(sqrt_D + q);
            pts[off] = (u + v - sub); num = 1;
        }
        return filterOutNotInAB(pts, off, num, A, B) - off;
    }

    static int filterOutNotInAB(final double[] nums, final int off, final int len, final double a, final double b) {
        int ret = off;
        for (int i = off, end = off + len; i < end; i++) {
            if (nums[i] >= a && nums[i] < b) { nums[ret++] = nums[i]; }
        }
        return ret;
    }

    // ---------------------------------------------------------------- DIAGNOSTIC cubic variants
    static final int ORIG = 0, REL_EPS = 1, REL_EPS_VCANCEL = 2;
    public static int VARIANT = ORIG;

    /** relative degeneracy tolerance, and v = -p/u to avoid Cardano cancellation (when VARIANT==2) */
    static int cubicRootsInABFix(final double d, double a, double b, double c,
                                 final double[] pts, final int off, final double A, final double B) {
        if (d == 0.0d) {
            final int num = quadraticRoots(a, b, c, pts, off);
            return filterOutNotInAB(pts, off, num, A, B) - off;
        }
        a /= d; b /= d; c /= d;
        final double sq_A = a * a;
        final double p = (1.0d / 3.0d) * ((-1.0d / 3.0d) * sq_A + b);
        final double sub = (1.0d / 3.0d) * a;
        final double q = (1.0d / 2.0d) * ((2.0d / 27.0d) * a * sq_A - sub * b + c);
        final double cb_p = p * p * p;
        final double qq = q * q;
        final double D = qq + cb_p;
        final double tol = EPS * Math.max(qq, Math.abs(cb_p));
        int num;
        if (withinD(D, tol)) {
            if (withinD(q, EPS * Math.abs(sub * sub * sub))) {
                pts[off] = (-sub); num = 1;
            } else {
                final double u = Math.cbrt(-q);
                pts[off] = (2.0d * u - sub); pts[off + 1] = (-u - sub); num = 2;
            }
        } else if (D < 0.0d) {
            final double phi = (1.0d / 3.0d) * Math.acos(-q / Math.sqrt(-cb_p));
            final double t = 2.0d * Math.sqrt(-p);
            pts[off] = (t * Math.cos(phi) - sub);
            pts[off + 1] = (-t * Math.cos(phi + (Math.PI / 3.0d)) - sub);
            pts[off + 2] = (-t * Math.cos(phi - (Math.PI / 3.0d)) - sub);
            num = 3;
        } else {
            final double sqrt_D = Math.sqrt(D);
            double u, v;
            if (VARIANT == REL_EPS_VCANCEL) {
                // pick the cube root without cancellation, then v = -p/u  (u*v == -p)
                u = (q > 0.0d) ? -Math.cbrt(sqrt_D + q) : Math.cbrt(sqrt_D - q);
                v = (u != 0.0d) ? -p / u : 0.0d;
            } else {
                u = Math.cbrt(sqrt_D - q);
                v = -Math.cbrt(sqrt_D + q);
            }
            pts[off] = (u + v - sub); num = 1;
        }
        return filterOutNotInAB(pts, off, num, A, B) - off;
    }

    static final int BR_TRIPLE = 0, BR_DOUBLE = 1, BR_TRIG = 2, BR_CARDANO = 3;
    static int branchOf(double d, double a, double b, double c) {
        if (d == 0.0d) return -1;
        a /= d; b /= d; c /= d;
        final double sq_A = a * a;
        final double p = (1.0d / 3.0d) * ((-1.0d / 3.0d) * sq_A + b);
        final double sub = (1.0d / 3.0d) * a;
        final double q = (1.0d / 2.0d) * ((2.0d / 27.0d) * a * sq_A - sub * b + c);
        final double D = q * q + p * p * p;
        if (within(D, 0.0d)) return within(q, 0.0d) ? BR_TRIPLE : BR_DOUBLE;
        return (D < 0.0d) ? BR_TRIG : BR_CARDANO;
    }

    public static int cubic(double d, double a, double b, double c, double[] pts, int off, double A, double B) {
        return (VARIANT == ORIG) ? cubicRootsInAB(d, a, b, c, pts, off, A, B)
                                 : cubicRootsInABFix(d, a, b, c, pts, off, A, B);
    }

    // ---------------------------------------------------------------- reference root finder
    public static final MathContext MC = new MathContext(60);
    static final BigDecimal TINY = new BigDecimal("1e-300");
    static final BigDecimal CONV = new BigDecimal("1e-50");
    static final BigDecimal THREE = BigDecimal.valueOf(3), TWO = BigDecimal.valueOf(2), FOUR = BigDecimal.valueOf(4);

    static double evalD(double d, double a, double b, double c, double x) { return ((d * x + a) * x + b) * x + c; }

    /** Newton refine x0 on d x^3 + a x^2 + b x + c with exact double coefficients; null if not converged. */
    static BigDecimal refine(double d, double a, double b, double c, double x0) {
        BigDecimal D = new BigDecimal(d), A = new BigDecimal(a), B = new BigDecimal(b), C = new BigDecimal(c);
        BigDecimal x = new BigDecimal(x0);
        for (int i = 0; i < 300; i++) {
            BigDecimal f = D.multiply(x, MC).add(A, MC).multiply(x, MC).add(B, MC).multiply(x, MC).add(C, MC);
            BigDecimal fp = D.multiply(THREE, MC).multiply(x, MC).add(A.multiply(TWO, MC), MC).multiply(x, MC).add(B, MC);
            if (fp.signum() == 0) return f.signum() == 0 ? x : null;
            BigDecimal step = f.divide(fp, MC);
            x = x.subtract(step, MC);
            if (step.abs().compareTo(x.abs().max(TINY).multiply(CONV, MC)) <= 0) return x;
        }
        return null;
    }

    /** exact number of distinct real roots */
    static int exactRealRootCount(double d, double a, double b, double c) {
        BigDecimal D = new BigDecimal(d), A = new BigDecimal(a), B = new BigDecimal(b), C = new BigDecimal(c);
        if (d == 0.0) {
            if (a == 0.0) return b != 0.0 ? 1 : 0;
            BigDecimal disc = B.multiply(B).subtract(FOUR.multiply(A).multiply(C));
            return disc.signum() > 0 ? 2 : (disc.signum() == 0 ? 1 : 0);
        }
        BigDecimal disc = BigDecimal.valueOf(18).multiply(D).multiply(A).multiply(B).multiply(C)
                .subtract(FOUR.multiply(A.pow(3)).multiply(C))
                .add(A.pow(2).multiply(B.pow(2)))
                .subtract(FOUR.multiply(D).multiply(B.pow(3)))
                .subtract(BigDecimal.valueOf(27).multiply(D.pow(2)).multiply(C.pow(2)));
        int s = disc.signum();
        if (s > 0) return 3;
        if (s < 0) return 1;
        return A.pow(2).subtract(THREE.multiply(D).multiply(B)).signum() == 0 ? 1 : 2;
    }

    /** all distinct real roots, high precision; null if the reference itself is uncertain (multiple root) */
    public static List<BigDecimal> referenceRoots(double d, double a, double b, double c) {
        List<BigDecimal> out = new ArrayList<>(3);
        int expected = exactRealRootCount(d, a, b, c);
        if (d == 0.0) {
            BigDecimal A = new BigDecimal(a), B = new BigDecimal(b), C = new BigDecimal(c);
            if (a == 0.0) {
                if (b != 0.0) out.add(C.negate().divide(B, MC));
                return out;
            }
            BigDecimal disc = B.multiply(B).subtract(FOUR.multiply(A).multiply(C));
            if (disc.signum() < 0) return out;
            if (disc.signum() == 0) return null;               // double root: ill-conditioned
            BigDecimal s = disc.sqrt(MC);
            BigDecimal r1 = B.negate().subtract(s).divide(TWO.multiply(A), MC);
            BigDecimal r2 = B.negate().add(s).divide(TWO.multiply(A), MC);
            out.add(r1.min(r2)); out.add(r1.max(r2));
            return out;
        }
        // brackets from the critical points of the cubic
        double discD = 4.0 * a * a - 12.0 * d * b;
        double M = 1.0 + (Math.abs(a) + Math.abs(b) + Math.abs(c)) / Math.abs(d);
        double[] pts;
        if (discD > 0.0) {
            double s = Math.sqrt(discD);
            double t1 = (-2.0 * a - s) / (6.0 * d), t2 = (-2.0 * a + s) / (6.0 * d);
            double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
            pts = new double[]{-M, lo, hi, M};
        } else {
            pts = new double[]{-M, M};
        }
        TreeSet<BigDecimal> roots = new TreeSet<>();
        for (int i = 0; i + 1 < pts.length; i++) {
            double x0 = pts[i], x1 = pts[i + 1];
            double f0 = evalD(d, a, b, c, x0), f1 = evalD(d, a, b, c, x1);
            if (f0 == 0.0) { BigDecimal r = refine(d, a, b, c, x0); if (r == null) return null; roots.add(r); }
            if (f1 == 0.0) { BigDecimal r = refine(d, a, b, c, x1); if (r == null) return null; roots.add(r); }
            if (f0 == 0.0 || f1 == 0.0 || (f0 > 0.0) == (f1 > 0.0)) continue;
            double loB = x0, hiB = x1;
            for (int it = 0; it < 200; it++) {
                double mid = 0.5 * (loB + hiB);
                if (mid == loB || mid == hiB) break;
                double fm = evalD(d, a, b, c, mid);
                if (fm == 0.0) { loB = hiB = mid; break; }
                if ((fm > 0.0) == (f0 > 0.0)) { loB = mid; } else { hiB = mid; }
            }
            BigDecimal r = refine(d, a, b, c, 0.5 * (loB + hiB));
            if (r == null) return null;
            roots.add(r);
        }
        // merge roots that are indistinguishable, then require agreement with the exact count
        List<BigDecimal> merged = new ArrayList<>();
        for (BigDecimal r : roots) {
            boolean dup = false;
            for (BigDecimal m : merged) {
                if (r.subtract(m, MC).abs().compareTo(r.abs().max(TINY).multiply(new BigDecimal("1e-30"), MC)) <= 0) { dup = true; break; }
            }
            if (!dup) merged.add(r);
        }
        if (merged.size() != expected) return null;    // near-multiple root: reference not trustworthy
        return merged;
    }

    // ---------------------------------------------------------------- statistics
    static final double FLOAT_ULP = 536870912.0; // 2^29 double ulps
    static final class Stats {
        final String name;
        long polys, illCond, expRoots, gotRoots, missed, spurious, countBad, gross, boundary;
        final ArrayList<Double> ulps = new ArrayList<>();
        final ArrayList<Double> ulpsOk = new ArrayList<>();   // roots of polys with the correct root count
        final long[] branch = new long[4];
        double maxUlp; String maxCase = "";
        double maxUlpOk; String maxOkCase = "";
        double maxAbs; String maxAbsCase = "";
        Stats(String n) { name = n; }

        void record(double d, double a, double b, double c, double[] got, int n, double A, double B) {
            polys++;
            int br = branchOf(d, a, b, c);
            if (br >= 0) branch[br]++;
            List<BigDecimal> refs = referenceRoots(d, a, b, c);
            if (refs == null) { illCond++; return; }
            BigDecimal bA = new BigDecimal(A), bB = new BigDecimal(B);
            List<BigDecimal> exp = new ArrayList<>();
            boolean nearBoundary = false;
            for (BigDecimal r : refs) {
                if (r.compareTo(bA) >= 0 && r.compareTo(bB) < 0) exp.add(r);
                // a root within a few ulps of A or B may legitimately fall on either side
                double rd = r.doubleValue();
                if (Math.abs(rd - A) < 8 * Math.ulp(A) || Math.abs(rd - B) < 8 * Math.ulp(B)) nearBoundary = true;
            }
            expRoots += exp.size(); gotRoots += n;
            if (nearBoundary) { boundary++; return; }

            boolean[] usedGot = new boolean[Math.max(n, 1)];
            int matched = 0;
            ArrayList<Double> mine = new ArrayList<>(3);
            ArrayList<String> mineCase = new ArrayList<>(3);
            for (BigDecimal e : exp) {
                int best = -1; double bestDist = Double.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (usedGot[i]) continue;
                    double dist = Math.abs(got[i] - e.doubleValue());
                    if (dist < bestDist) { bestDist = dist; best = i; }
                }
                // match generously: a found-but-wrong root counts as a large error, not as missed+spurious
                if (best < 0 || bestDist > 0.05) { missed++; continue; }
                usedGot[best] = true; matched++;
                double refD = e.doubleValue();
                double abs = e.subtract(new BigDecimal(got[best]), MC).abs().doubleValue();
                double u = abs / Math.ulp(refD);
                ulps.add(u);
                mine.add(u);
                mineCase.add(String.format("d=%s a=%s b=%s c=%s got=%s ref=%s", d, a, b, c, got[best], e.round(new MathContext(20))));
                if (u > FLOAT_ULP) gross++;
                if (u > maxUlp) {
                    maxUlp = u;
                    maxCase = String.format("d=%s a=%s b=%s c=%s got=%s ref=%s", d, a, b, c, got[best], e.round(new MathContext(20)));
                }
                if (abs > maxAbs) {
                    maxAbs = abs;
                    maxAbsCase = String.format("d=%s a=%s b=%s c=%s got=%s ref=%s", d, a, b, c, got[best], e.round(new MathContext(20)));
                }
            }
            for (int i = 0; i < n; i++) if (!usedGot[i]) spurious++;
            if (matched != exp.size() || n != exp.size()) {
                countBad++;
            } else {
                for (int i = 0; i < mine.size(); i++) {
                    double u = mine.get(i);
                    ulpsOk.add(u);
                    if (u > maxUlpOk) { maxUlpOk = u; maxOkCase = mineCase.get(i); }
                }
            }
        }

        static double pct(List<Double> l, double p) {
            if (l.isEmpty()) return Double.NaN;
            int i = (int) Math.min(l.size() - 1, Math.floor(p * l.size()));
            return l.get(i);
        }
        static double frac(List<Double> l, double le) { long k = 0; for (double u : l) if (u <= le) k++; return l.isEmpty() ? Double.NaN : (100.0 * k / l.size()); }

        void print(StringBuilder sb) {
            Collections.sort(ulps); Collections.sort(ulpsOk);
            long counted = polys - illCond - boundary;
            long brSum = branch[0] + branch[1] + branch[2] + branch[3];
            sb.append(String.format("%-46s polys=%6d (illCond %4d, nearBnd %4d) expRoots=%6d gotRoots=%6d | lostRoots=%5d spurious=%4d wrongCountPolys=%6.2f%%  maxAbsErr(t)=%.3g%s%n",
                    name, polys, illCond, boundary, expRoots, gotRoots, missed, spurious,
                    counted > 0 ? 100.0 * countBad / counted : Double.NaN, maxAbs,
                    brSum > 0 ? String.format("  branches: trig %.1f%% cardano %.1f%% double %.1f%% triple %.1f%%",
                            100.0 * branch[BR_TRIG] / brSum, 100.0 * branch[BR_CARDANO] / brSum,
                            100.0 * branch[BR_DOUBLE] / brSum, 100.0 * branch[BR_TRIPLE] / brSum) : ""));
            sb.append(String.format("%-46s   roots of correct-count polys=%6d: <=0.5ulp %5.1f%%  <=1 %5.1f%%  <=2 %5.1f%%  <=10 %5.1f%%  <=100 %5.1f%% | median %8.2f  p99 %12.2f  max %14.2f%n",
                    "", ulpsOk.size(), frac(ulpsOk, 0.5), frac(ulpsOk, 1), frac(ulpsOk, 2), frac(ulpsOk, 10), frac(ulpsOk, 100),
                    pct(ulpsOk, 0.5), pct(ulpsOk, 0.99), maxUlpOk));
            if (maxUlpOk > 10) sb.append("      worst: ").append(maxOkCase).append('\n');
            if (missed + spurious > 0) sb.append("      worst abs (all polys): ").append(maxAbsCase).append('\n');
        }
    }

    // ---------------------------------------------------------------- scenarios
    static double A = 1e-6, B = 1.0 - 1e-6;
    static Random rnd = new Random(0x5EEDL);
    static double uni(double lo, double hi) { return lo + (hi - lo) * rnd.nextDouble(); }
    static double logUni(double lo, double hi) { return Math.exp(uni(Math.log(lo), Math.log(hi))); }
    static double scale() { double s = Math.pow(10.0, uni(-3, 3)); return rnd.nextBoolean() ? s : -s; }

    // ---- degree 2, roots then filtered to [A,B) exactly as findSubdivPoints does
    static void quad(Stats st, int n, java.util.function.Supplier<double[]> rootGen) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] rr = rootGen.get();
            double a = scale(), b = -a * (rr[0] + rr[1]), c = a * rr[0] * rr[1];
            int k = quadraticRoots(a, b, c, r, 0);
            k = filterOutNotInAB(r, 0, k, A, B);
            st.record(0, a, b, c, r, k, A, B);
        }
    }

    static void quadMarlinDx(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double x1 = uni(0, span), x2 = uni(0, span), x3 = uni(0, span), x4 = uni(0, span);
            double ax = 3.0 * (x2 - x3) + x4 - x1, bx = 3.0 * (x1 - 2.0 * x2 + x3), cx = 3.0 * (x2 - x1);
            double dax = 3.0 * ax, dbx = 2.0 * bx;
            int k = quadraticRoots(dax, dbx, cx, r, 0);
            k = filterOutNotInAB(r, 0, k, A, B);
            st.record(0, dax, dbx, cx, r, k, A, B);
        }
    }

    static void quadMarlinInf(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
            double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
            double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
            double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
            double a = dax * dby - dbx * day, b = 2.0 * (cy * dax - day * cx), c = cy * dbx - cx * dby;
            int k = quadraticRoots(a, b, c, r, 0);
            k = filterOutNotInAB(r, 0, k, A, B);
            st.record(0, a, b, c, r, k, A, B);
        }
    }

    // ---- degree 3 through cubicRootsInAB(.., A, B)
    static void cub(Stats st, int n, java.util.function.Supplier<double[]> rootGen) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] rr = rootGen.get();
            double d = scale();
            double a = -d * (rr[0] + rr[1] + rr[2]);
            double b = d * (rr[0] * rr[1] + rr[0] * rr[2] + rr[1] * rr[2]);
            double c = -d * rr[0] * rr[1] * rr[2];
            int k = cubic(d, a, b, c, r, 0, A, B);
            st.record(d, a, b, c, r, k, A, B);
        }
    }

    /** one real root in [A,B), complex pair */
    static void cub1(Stats st, int n) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double r1 = uni(A, B), d = scale();
            double re = uni(-2, 2), im = uni(0.01, 2);
            double p = -2 * re, q = re * re + im * im;
            double a = d * (p - r1), b = d * (q - r1 * p), c = -d * r1 * q;
            int k = cubic(d, a, b, c, r, 0, A, B);
            st.record(d, a, b, c, r, k, A, B);
        }
    }

    static void cubMarlinX(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double x1 = uni(0, span), x2 = uni(0, span), x3 = uni(0, span), x4 = uni(0, span);
            double ax = 3.0 * (x2 - x3) + x4 - x1, bx = 3.0 * (x1 - 2.0 * x2 + x3), cx = 3.0 * (x2 - x1), dx = x1;
            double lo = Math.min(Math.min(x1, x2), Math.min(x3, x4)), hi = Math.max(Math.max(x1, x2), Math.max(x3, x4));
            double x0 = uni(lo, hi);
            int k = cubic(ax, bx, cx, dx - x0, r, 0, A, B);
            st.record(ax, bx, cx, dx - x0, r, k, A, B);
        }
    }

    static void cubMarlinPerp(Stats st, int n, double span) {
        double[] r = new double[4];
        for (int i = 0; i < n; i++) {
            double[] X = new double[4], Y = new double[4];
            for (int j = 0; j < 4; j++) { X[j] = uni(0, span); Y[j] = uni(0, span); }
            double ax = 3.0 * (X[1] - X[2]) + X[3] - X[0], bx = 3.0 * (X[0] - 2.0 * X[1] + X[2]), cx = 3.0 * (X[1] - X[0]);
            double ay = 3.0 * (Y[1] - Y[2]) + Y[3] - Y[0], by = 3.0 * (Y[0] - 2.0 * Y[1] + Y[2]), cy = 3.0 * (Y[1] - Y[0]);
            double dax = 3 * ax, dbx = 2 * bx, day = 3 * ay, dby = 2 * by;
            double a = 2.0 * (dax * dax + day * day), b = 3.0 * (dax * dbx + day * dby);
            double c = 2.0 * (dax * cx + day * cy) + dbx * dbx + dby * dby, d = dbx * cx + dby * cy;
            int k = cubic(a, b, c, d, r, 0, A, B);
            st.record(a, b, c, d, r, k, A, B);
        }
    }

    static double[] two(double r1, double r2) { return new double[]{r1, r2}; }
    static double[] three(double r1, double r2, double r3) { return new double[]{r1, r2, r3}; }

    public static void main(String[] args) {
        final int N = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        if (args.length > 1) { double e = Double.parseDouble(args[1]); A = e; B = 1.0 - e; }
        StringBuilder sb = new StringBuilder();
        sb.append("Marlin Helpers root solvers on t in [A,B), A=" + A + ", B=" + B + "\n");
        sb.append("N=" + N + " polynomials per scenario. Reference: all real roots of the exact double coefficients,\n");
        sb.append("found independently (bracketing + 60-digit BigDecimal Newton), cross-checked with the exact discriminant.\n");
        sb.append("Expected = reference roots inside [A,B). illCond = reference has a (near-)multiple root; nearBnd = a root sits\n");
        sb.append("within 8 ulps of A or B (either side is defensible); both are excluded from the count statistics.\n");
        sb.append("gross = matched root off by more than 1 float ulp (2^29 double ulps). ulp stats are over matched roots.\n\n");
        Stats s;

        sb.append("== degree 2: quadraticRoots + filterOutNotInAB(.., A, B) as in findSubdivPoints ==\n");
        s = new Stats("quad: 2 roots uniform in [A,B)");
        quad(s, N, () -> two(uni(A, B), uni(A, B))); s.print(sb);
        s = new Stats("quad: 1 root log-uniform in [1e-6,1e-3], 1 mid");
        quad(s, N, () -> two(logUni(1e-6, 1e-3), uni(0.2, 0.8))); s.print(sb);
        s = new Stats("quad: 1 root near B (1-1e-3..1-1e-6), 1 mid");
        quad(s, N, () -> two(1.0 - logUni(1e-6, 1e-3), uni(0.2, 0.8))); s.print(sb);
        s = new Stats("quad: 1 root inside, 1 outside (t<0 or t>1)");
        quad(s, N, () -> two(uni(A, B), rnd.nextBoolean() ? uni(-3, -1e-3) : uni(1.0 + 1e-3, 3))); s.print(sb);
        for (int k = 1; k <= 7; k++) {
            final double delta = Math.pow(10, -k);
            s = new Stats("quad: close roots in [A,B), gap=1e-" + k);
            quad(s, N, () -> { double r1 = uni(0.1, 0.9); return two(r1, r1 + delta); }); s.print(sb);
        }
        s = new Stats("quad: Marlin dxRoots, coords [0,4096]"); quadMarlinDx(s, N, 4096); s.print(sb);
        s = new Stats("quad: Marlin dxRoots, coords [0,64]"); quadMarlinDx(s, N, 64); s.print(sb);
        s = new Stats("quad: Marlin infPoints, coords [0,4096]"); quadMarlinInf(s, N, 4096); s.print(sb);

        for (int pass = 0; pass < 3; pass++) {
            VARIANT = pass;
            sb.append(pass == ORIG ? "\n== degree 3: cubicRootsInAB(.., 1e-6, 1-1e-6), code as in Helpers.java ==\n"
                    : pass == REL_EPS ? "\n== degree 3 DIAGNOSTIC A: within(D,0) -> |D| <= EPS*max(q^2,|p^3|) (relative) ==\n"
                    : "\n== degree 3 DIAGNOSTIC B: relative tolerance + Cardano v = -p/u (no sqrt_D+q cancellation) ==\n");
            s = new Stats("cubic: 3 roots uniform in [A,B)");
            cub(s, N, () -> three(uni(A, B), uni(A, B), uni(A, B))); s.print(sb);
            s = new Stats("cubic: 3 roots in [1e-6,0.1]");
            cub(s, N, () -> three(uni(A, 0.1), uni(A, 0.1), uni(A, 0.1))); s.print(sb);
            s = new Stats("cubic: 3 roots log-uniform in [1e-6,1)");
            cub(s, N, () -> three(logUni(A, B), logUni(A, B), logUni(A, B))); s.print(sb);
            s = new Stats("cubic: 2 roots in [A,B), 1 outside");
            cub(s, N, () -> three(uni(A, B), uni(A, B), rnd.nextBoolean() ? uni(-3, -1e-3) : uni(1.0 + 1e-3, 3))); s.print(sb);
            s = new Stats("cubic: 1 root in [A,B), 2 outside");
            cub(s, N, () -> three(uni(A, B), uni(-3, -1e-3), uni(1.0 + 1e-3, 3))); s.print(sb);
            s = new Stats("cubic: 1 real root in [A,B) (Cardano branch)"); cub1(s, N); s.print(sb);
            for (int k = 1; k <= 7; k++) {
                final double delta = Math.pow(10, -k);
                s = new Stats("cubic: close pair in [A,B), gap=1e-" + k);
                cub(s, N, () -> { double r1 = uni(0.1, 0.9); return three(r1, r1 + delta, uni(0.1, 0.9)); }); s.print(sb);
            }
            s = new Stats("cubic: Marlin xPoints, coords [0,4096]"); cubMarlinX(s, N, 4096); s.print(sb);
            s = new Stats("cubic: Marlin xPoints, coords [0,64]"); cubMarlinX(s, N, 64); s.print(sb);
            s = new Stats("cubic: Marlin perpendiculardfddf, [0,4096]"); cubMarlinPerp(s, N, 4096); s.print(sb);
            s = new Stats("cubic: Marlin perpendiculardfddf, [0,64]"); cubMarlinPerp(s, N, 64); s.print(sb);
        }
        System.out.print(sb);
    }
}
