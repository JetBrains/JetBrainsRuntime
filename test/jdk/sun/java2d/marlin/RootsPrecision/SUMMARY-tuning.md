# Tuning the cubic degeneracy threshold, and what actually helped

Question: can the cubic solver be improved by tuning the error thresholds?
Answer, measured: no. Any threshold behaves either exactly like no threshold at all, or worse.
The improvement available at that point in the algorithm is a Newton step, not a better constant.

Harness: ThresholdSweep.java. Coefficients a,b,c,d drawn over 20 decades (1e-10 .. 1e10, random signs),
plus three structured shapes. Every variant is scored against the same exact reference roots
(RootsUlpEval2.referenceRoots, 60-digit), computed once per polynomial. N = 40000 per shape.
Raw output: sweep-20dec.txt.

## The threshold family tested

Derived rather than guessed. For y^3 + 3py + 2q the discriminant is Delta = -108 D with D = q^2 + p^3;
for a near-double pair separated by delta with the third root a distance g away, Delta ~ delta^2 g^4
and g^2 ~ -3p, so delta ~ sqrt(-108 D) / g^2 and

    |D| <= tau^2 * p^2 / 12

declares a double root exactly when the two roots are closer than tau IN T UNITS. That is scale-free in
t, the domain Marlin works in -- unlike an absolute test on D (loses small roots) or one relative to
max(q^2,|p^3|) (merges distinct roots when a large third root sets the scale). Both of those were
measured earlier and rejected; this one is the principled version.

## Result: the threshold does nothing

wild coefficients, 20 decades (39757 polys, 12471 true roots in [1e-6, 1-1e-6)):

| threshold | Newton steps | lost | spurious | <=1 ulp | median | p99 |
|---|---:|---:|---:|---:|---:|---:|
| sign of D (no tolerance) | 0 | 3216 | 2041 | 50.95% | 0.95 | 1.47e13 |
| tau = 1e-14 | 0 | 3216 | 2041 | 50.95% | 0.95 | 1.47e13 |
| tau = 1e-12 | 0 | 3216 | 2041 | 50.95% | 0.95 | 1.47e13 |
| tau = 1e-10 | 0 | 3216 | 2041 | 50.95% | 0.95 | 1.47e13 |
| tau = 1e-8 | 0 | 3216 | 2041 | 50.95% | 0.95 | 1.47e13 |
| tau = 1e-6 | 0 | 3216 | 2041 | 50.36% | 0.98 | 1.49e13 |
| tau = 1e-4 | 0 | 3529 | 2188 | 49.62% | 1.03 | 1.20e13 |

Everything from 1e-14 to 1e-8 is bit-identical to the sign test: on real input D is never small enough
relative to p^2 to trip it without already being 0. At 1e-6 spurious roots start appearing, and 1e-4
loses 313 more roots. On the structured shape "3 roots in [A,B)" tau = 1e-4 introduces 18 lost roots
where every smaller tau loses none. There is no setting that wins anywhere.

## Result: one Newton step is worth a lot

Same sweep, threshold fixed at the sign test, varying the number of Newton steps on the original cubic
(fma Horner for both f and f'):

| shape | steps | lost | spurious | <=1 ulp | <=2 ulp | median | p99 |
|---|---:|---:|---:|---:|---:|---:|---:|
| wild, 20 decades | 0 | 3216 | 2041 | 50.95% | 55.96% | 0.95 | 1.47e13 |
| wild, 20 decades | 1 | 2827 | 1381 | 88.29% | 89.03% | 0.31 | 4.47e11 |
| wild, 20 decades | 2 | 2668 | 1223 | 94.44% | 94.94% | 0.28 | 7.11e9 |
| 3 roots in [A,B) | 0 | 0 | 0 | 20.39% | 35.73% | 3.43 | 854.54 |
| 3 roots in [A,B) | 1 | 0 | 0 | 41.51% | 57.04% | 1.44 | 474.21 |
| 3 roots in [A,B) | 2 | 0 | 0 | 41.86% | 57.03% | 1.43 | 447.72 |
| Marlin xPoints | 0 | 0 | 0 | 31.17% | 50.34% | 1.98 | 1341.44 |
| Marlin xPoints | 1 | 0 | 0 | 89.95% | 97.57% | 0.37 | 3.19 |
| Marlin xPoints | 2 | 0 | 0 | 90.03% | 97.65% | 0.36 | 3.03 |
| Marlin perpendiculardfddf | 0 | 0 | 0 | 33.20% | 54.95% | 1.73 | 32.07 |
| Marlin perpendiculardfddf | 1 | 0 | 0 | 60.10% | 78.60% | 0.72 | 14.47 |
| Marlin perpendiculardfddf | 2 | 0 | 0 | 60.32% | 79.06% | 0.72 | 14.88 |

One step is the sweet spot: on Marlin-shaped input it takes xPoints from 50% to 98% of roots within
2 ulps and the p99 from 1341 to 3.2 ulps, and it costs 2 fma plus one division per root. The second step
only pays on the pathological 20-decade input (lost 2827 -> 2668); it is a one-line change if wanted.
Newton cannot fix a multiple root (f' -> 0 there), which is why the ill-conditioned tails remain.

## Committed

Sign of D, no tolerance, plus one Newton step. Verified: sun.java2d.marlin compiles clean; no NaN or
infinite root over 2e6 polynomials with 20-decade coefficients, exact double and triple roots, and the
d == 0 path. The refinement is skipped when f' == 0 so a multiple root keeps its unrefined value.
