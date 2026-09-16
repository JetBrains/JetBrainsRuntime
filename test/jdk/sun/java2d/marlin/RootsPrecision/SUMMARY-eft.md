# Normalisation, and where error-free transforms actually pay

## How the cubic is normalised today

    a /= d;  b /= d;  c /= d;

Three plain divisions, each correctly rounded but each carrying up to 0.5 ulp of relative error, and
NOTHING downstream compensates them. Every fma/TwoSum compensation in p, q, p^3 and D is therefore an
exact evaluation of the WRONG cubic: the one with coefficients fl(a/d), fl(b/d), fl(c/d) rather than
a/d, b/d, c/d. For a well-conditioned root that perturbation is worth about 1 ulp; for a near-double
root it is amplified to sqrt(eps) ~ 1e-8, and no downstream precision can recover it.

The Newton step at the end hides this for root VALUES, because it refines against the ORIGINAL
coefficients (d, a0, b0, c0) and so re-targets the true polynomial. What it cannot hide is a wrong
BRANCH: a root that was never emitted cannot be refined.

## Error-free transforms are already in use

Every `Math.fma(x, y, -(x*y))` in Helpers IS the error term of twoProduct, and twoSumErr() is Knuth's
twoSum. The cubic already carries the exact error of a*a, sq_A*a, a*b, 3*b, 9*ab, 27*c, p*p, sq_p*p and
q*q. So the question is not whether to use twoProduct but where it is still missing. Two candidates were
implemented and measured (EFTSolve.java, 40000 polynomials per shape, scored against 60-digit roots).

## Candidate 1: a division-free discriminant -- REJECTED

The branch decision needs no division. With ph = 3bd - a^2 and qh = 2a^3 - 9abd + 27cd^2,

    p = ph/(9d^2),  q = qh/(54d^3),  D = q^2 + p^3 = (qh^2 + 4*ph^3) / (2916 d^6)

and d^6 > 0, so sign(D) == sign(qh^2 + 4*ph^3), computable from the original coefficients in
double-double with twoProduct/twoSum. Implemented, and it does not work well enough to be worth it:

- it is NOT exact. Against an 80-digit evaluation, 690 of 20000 signs disagree at 20-decade coefficient
  range: qh^2 reaches 1e60 while the true qh^2 + 4ph^3 can be 1e20, so the cancellation exceeds the
  ~32 digits double-double provides.
- it buys nothing measurable: lost roots 2827 -> 2815 and spurious 1381 -> 1336 on wild coefficients,
  and bit-identical results on all three structured shapes, where the branch is already always right.

Roughly 40 lines of double-double in a hot path for no measurable gain. Not applied.

## Candidate 2: compensated Horner in the Newton residual -- APPLIED

f(t) near a root is pure cancellation: the plain fma Horner residual is mostly rounding noise, and the
Newton step divides that noise by f'. Evaluating f with a compensated Horner scheme (twoProduct on every
product, twoSum on every sum, the errors accumulated in e and added back) makes one step land on the
correctly rounded root.

| shape | variant | lost | spurious | <=1 ulp | <=2 ulp | median | p99 |
|---|---|---:|---:|---:|---:|---:|---:|
| Marlin xPoints | fma Horner residual | 0 | 0 | 89.95% | 97.57% | 0.37 | 3.19 |
| Marlin xPoints | **compensated Horner** | 0 | 0 | **100.00%** | **100.00%** | **0.25** | **0.50** |
| Marlin perpendiculardfddf | fma Horner residual | 0 | 0 | 60.10% | 78.60% | 0.72 | 14.47 |
| Marlin perpendiculardfddf | **compensated Horner** | 0 | 0 | **100.00%** | **100.00%** | **0.25** | **0.49** |
| 3 roots in [A,B) | fma Horner residual | 0 | 0 | 41.51% | 57.04% | 1.44 | 474.21 |
| 3 roots in [A,B) | **compensated Horner** | 0 | 0 | **100.00%** | **100.00%** | **0.25** | **0.49** |
| wild, 20 decades | fma Horner residual | 2827 | 1381 | 88.29% | 89.03% | 0.31 | 4.5e11 |
| wild, 20 decades | **compensated Horner** | 2827 | 1381 | 88.72% | 89.04% | 0.29 | 4.5e11 |

A p99 of 0.49 ulp with a median of 0.25 is the signature of correctly rounded results: the remaining
error is the rounding of the exact root to a double, nothing more. Cost is a handful of fma per root,
once.

On 20-decade coefficients the accuracy improves but the lost-root count does not: those roots are lost
in the branch decision or in the normalisation conditioning, before Newton ever sees them, which is the
same limit candidate 1 failed to lift. A second compensated step recovers some (lost 2827 -> 2668,
spurious 1381 -> 1223) and is a one-line change if that input class ever matters.

Verified: sun.java2d.marlin compiles clean; no NaN or infinite root over 2e6 polynomials at 20-decade
coefficient range including exact double and triple roots and the d == 0 path.
