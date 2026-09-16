# Marlin root solvers restricted to t in [eps, 1-eps), eps = 1e-6

Same measurement as SUMMARY.md, but only reference roots inside [A,B) are expected, matching real use:
- degree 2: quadraticRoots + filterOutNotInAB(ts,0,ret,T_A,T_B) as Helpers.findSubdivPoints does
- degree 3: cubicRootsInAB(..., A, B) with A = 1e-6, B = 1 - 1e-6

Reference: all real roots of the exact double coefficients, found independently (double bracketing via the
cubic's critical points + 60-digit BigDecimal Newton), cross-checked against the exact discriminant.
N = 20000 polynomials per scenario. ulp percentiles are over roots of polynomials whose root COUNT was right,
so a lost root shows up as "lost", not as a huge ulp value. Data: report-interval.txt, report-eps1e-4.txt.

## degree 2 -- quadraticRoots, roots filtered to [A,B)

| scenario | median | <=1 ulp | <=2 ulp | p99 | max | lost | spurious |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2 roots uniform in [A,B) | 0.57 | 70% | 85% | 33 | 8408 | 0 | 0 |
| 1 root in [1e-6,1e-3], 1 mid | 0.35 | 95% | 100% | 1.3 | 2.1 | 0 | 0 |
| 1 root in [1-1e-3, 1-1e-6], 1 mid | 0.54 | 76% | 95% | 3.3 | 6.1 | 0 | 0 |
| 1 root inside, 1 outside | 0.36 | 92% | 99% | 1.8 | 38 | 0 | 0 |
| Marlin dxRoots, coords [0,4096] | 0.39 | 90% | 99% | 2.4 | 26 | 0 | 0 |
| Marlin dxRoots, coords [0,64] | 0.39 | 91% | 99% | 2.1 | 21 | 0 | 0 |
| Marlin infPoints, coords [0,4096] | 0.40 | 89% | 98% | 2.9 | 42 | 0 | 0 |
| close roots gap 1e-3 / 1e-5 / 1e-7 | 121 / 11825 / 1.2e6 | | | | | 0 | 0 |

The interval filter is exact (a comparison), never loses or invents a root, and it removes the
relative-error blowup for roots near 0 seen without it. Error still grows as 1/gap for close roots
(uncompensated b*b - 4ac); max absolute t error stays <= 1.6e-9 even at gap 1e-7.

## degree 3 -- cubicRootsInAB(..., 1e-6, 1-1e-6), code as in Helpers.java

| scenario | branch mix (trig/Cardano/double) | median | <=2 ulp | p99 | lost roots | wrong-count polys | max abs t err |
|---|---|---:|---:|---:|---:|---:|---:|
| 3 roots uniform in [A,B) | 94 / 0 / 6 % | 5.9 | 25% | 380 | 1252/60000 | 6.25% | 0.050 |
| 3 roots in [1e-6, 0.1] | 0 / 0 / 99 % | - | - | - | 20137/60000 | 100% | 0.050 |
| 3 roots log-uniform in [1e-6,1) | 23 / 0 / 56 % | 7.3 | 37% | 3.3e6 | 19645/60000 | 77% | 0.050 |
| 2 roots in [A,B), 1 outside | 99.9 / 0 / 0.1 % | 4.7 | 30% | 569 | 19 | 0.10% | 0.008 |
| 1 root in [A,B), 2 outside | 100 / 0 / 0 % | 3.7 | 29% | 206 | 0 | 0.00% | 4e-14 |
| 1 real root in [A,B) (Cardano) | 0 / 99.9 / 0.1 % | 2.2 | 48% | 2419 | 0 | 0.09% (17 spurious) | 0.039 |
| close pair gap 1e-1 | 82 / 0 / 18 % | 10.0 | 15% | 238 | 3562 | 17.8% | 0.050 |
| close pair gap 1e-2 | 60 / 0 / 41 % | 41 | 14% | 742 | 8115 | 40.5% | 0.050 |
| close pair gap <= 1e-4 | 0 / 0 / 99 % | - | - | - | ~20000 | 100% | 0.050 |
| Marlin xPoints, coords [0,4096] | 44 / 57 / 0 % | 3.5 | 36% | 7736 | 0 | 0.00% | 4.0e-9 |
| Marlin xPoints, coords [0,64] | 43 / 57 / 0 % | 3.4 | 36% | 6556 | 0 | 0.00% | 1.7e-8 |
| Marlin perpendiculardfddf, [0,4096] | 37 / 63 / 0 % | 3.1 | 37% | 131 | 5 | 0.05% | 0.017 |
| Marlin perpendiculardfddf, [0,64] | 37 / 63 / 0 % | 3.2 | 37% | 139 | 8 | 0.12% | 0.036 |

Branch mix is measured with the ORIGINAL absolute test, in every pass. Note how exactly it tracks the
wrong-count rate: the "double root" branch share equals the fraction of 3-root cubics that lose a root.

## Effect of the two fixes (same scenarios, same seeds)

A = relative degeneracy tolerance, |D| <= EPS*max(q^2, |p^3|) instead of |D| <= 1e-9.
B = A plus Cardano without cancellation: u = sign-selected cbrt, v = -p/u.

| scenario | original | A | B |
|---|---|---|---|
| 3 roots uniform in [A,B): wrong-count polys | 6.25% | 0.00% | 0.00% |
| 3 roots in [1e-6,0.1]: wrong-count polys | 100% | 0.00% | 0.00% |
| close pair gap 1e-4: wrong-count polys | 100% | 0.00% | 0.00% |
| close pair gap 1e-5: wrong-count polys | 100% | 0.00% | 0.00% |
| Cardano branch: median / p99 / max ulp | 2.2 / 2419 / 1.1e16 | 2.1 / 2163 / 4.6e7 | 1.3 / 56 / 2623 |
| Cardano branch: max abs t err | 0.039 | 1.2e-9 | 1.8e-13 |
| xPoints [0,4096]: median / p99 ulp | 3.5 / 7736 | 3.5 / 10170 | 2.7 / 2169 |
| perpendiculardfddf [0,4096]: p99 / max ulp | 131 / 2.4e8 | 146 / 4.3e6 | 52 / 4386 |
| perpendiculardfddf [0,64]: wrong-count polys | 0.12% | 0.00% | 0.00% |

Both fixes are 2-3 lines and cost nothing (B replaces a cbrt with a divide).
Below gap 1e-6 the remaining error is inherent conditioning of a near-double root, not a solver defect.

eps = 1e-4 (Marlin's actual T_ERR) reproduces every number above: 5.98% wrong-count for 3 uniform roots,
0.00-0.08% for the Marlin-shaped cubics, 0 lost roots for all degree-2 cases (report-eps1e-4.txt).

## Metric correction and pairing-free check (true-abs-err.txt, TrueAbsErr.java)

The "maxAbsErr(t)" column in report-interval.txt is computed over roots that the greedy matcher PAIRED,
with a 0.05 pairing cap, so it is a pairing-dependent quantity and could in principle be truncated by
the cap. TrueAbsErr re-measures it without any pairing or cap: for every true root in [A,B) it reports
the distance to the NEAREST root the solver returned. Results (N=20000, cubic):

| scenario | variant | median | p90 | p99 | max | roots off by >1e-6 | >1e-3 |
|---|---|---:|---:|---:|---:|---:|---:|
| 3 roots uniform in [A,B) | original | 3.3e-16 | 1.0e-14 | 0.011 | 0.0484 | 6.0% | 4.3% |
| 3 roots uniform in [A,B) | relTol | 3.3e-16 | 6.0e-15 | 1.3e-13 | 2.0e-10 | 0.0% | 0.0% |
| 3 roots in [1e-6,0.1] | original | 0.0037 | 0.012 | 0.026 | 0.0435 | 99.5% | 78.4% |
| 3 roots in [1e-6,0.1] | relTol | 3.1e-17 | 6.0e-16 | 1.3e-14 | 1.7e-11 | 0.0% | 0.0% |
| close pair gap 1e-2 | original | 1.7e-14 | 0.0052 | 0.0062 | 0.0100 | 40.5% | 29.9% |
| close pair gap 1e-2 | relTol | 4.8e-15 | 8.5e-14 | 1.4e-12 | 2.0e-10 | 0.0% | 0.0% |
| 3 roots log-uniform in [1e-6,1) | original | 4.1e-05 | 0.0019 | 0.012 | 0.0486 | 67.3% | 16.2% |
| 3 roots log-uniform in [1e-6,1) | relTol | 6.9e-18 | 1.5e-15 | 4.6e-08 | 5.2e-06 | 0.4% | 0.0% |

Conclusions, now pairing-free:
- The cap never actually bound: the largest nearest-root distance observed is 0.0486 < 0.05, so the
  reported ~0.05 magnitudes stand. The metric was imprecise in definition, not in result.
- A "lost root" never means a wildly misplaced value: the solver simply returns 2 roots where there are 3,
  so one true root is only covered by a neighbour up to ~0.05 away.
- The relative-tolerance fix removes it outright: 6.0% -> 0.0% of roots off by more than 1e-6 for uniform
  roots in [A,B), 99.5% -> 0.0% for roots clustered in [1e-6,0.1].
- relTol and relTol+v are bit-identical on these three-real-root scenarios, as expected: v = -p/u only
  affects the one-real-root Cardano branch. That is an internal consistency check on the harness.

## Known limitations of the harness
- branchOf() always evaluates the ORIGINAL absolute test, in all three passes, so the branch histogram
  describes the input distribution under current Helpers.java, not under the diagnostic variants.
- Matching in RootsUlpEval2 is greedy in ascending root order; TrueAbsErr above is the pairing-free check.
- Polynomials whose reference is itself uncertain (near-multiple root) are excluded and counted as illCond;
  for every headline scenario illCond = 0, so the headline numbers carry no selection bias.
