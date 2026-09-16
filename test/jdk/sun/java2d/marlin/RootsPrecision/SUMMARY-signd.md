# Computing the sign of D properly

The sign of D = q^2 + p^3 selects which branch the cubic solver takes, so a wrong sign does not make a
root inaccurate -- it makes the root never be returned. It was the last real defect left.

## Why forming D cannot work

Three attempts, all scored against a 120-digit evaluation of the exact discriminant
(Delta = 18abcd - 4a^3c + a^2b^2 - 4b^3d - 27c^2d^2, D = -Delta/(108 d^4)), 60000 random cubics per
coefficient range:

| method | 6 decades | 12 decades | 20 decades | 30 decades |
|---|---:|---:|---:|---:|
| compensated p,q,p^3,D then sign(D) | 0.0033% | 4.04% | 9.51% | 13.18% |
| double-double qh^2 + 4ph^3, no division | 0.0000% | 0.0133% | 3.25% | 9.14% |
| **sign(f(t1) * f(t2)) at the critical points** | **0** | **0** | **0** | **0** |

Any expression of the form (big) + (big) with the result near zero is hopeless: at 20 decades q^2 reaches
1e60 while the true q^2 + p^3 can be 1e20, so 40 digits are cancelled away and neither double nor
double-double (~32 digits) retains the sign. Adding precision to the *same expression* does not converge.

## What works: don't form D at all

f has three distinct real roots exactly when it takes opposite signs at its two critical points, so

    sign(D) == sign(f(t1) * f(t2)),    f'(t1) = f'(t2) = 0

Three facts make this cheap and exact:

1. t1, t2 are the roots of f'(t) = 3d t^2 + 2a t + b, obtained from the Kahan fma discriminant already
   used by quadraticRoots (<= 2 ulps). If f' has no real root, f is monotonic: a single real root, no
   evaluation needed.
2. f(t1) is evaluated by COMPENSATED HORNER. This is the load-bearing part: near a double root f(t1) is
   itself almost a complete cancellation, which is exactly when the sign matters.
3. The critical points need almost no accuracy, because f'(t1) = 0 makes f stationary there: an error e
   in t1 perturbs f(t1) only by f''(t1) * e^2 / 2, a second-order effect. This is why the approach
   succeeds where direct evaluation fails -- the quantity being signed is not a cancellation of two
   large terms but a stationary value of a compensated polynomial evaluation.

No product is formed either: the two signs are compared directly, so there is nothing to overflow.

## The compensated evaluation is what carries it

Cubics built with a near-double root pair (gap delta, third root elsewhere), 20000 per gap, wrong signs:

| gap | compensated D | critical points + plain fma Horner | critical points + compensated Horner |
|---|---:|---:|---:|
| 1e-2 | 0 | 0 | 0 |
| 1e-6 | 0 | 0 | 0 |
| 1e-8 | 2956 | 1148 | **0** |
| 1e-10 | 5185 | 2042 | **0** |
| 1e-12 | 4812 | 1942 | **0** |
| exact double root | 5025 | 1909 | **0** |

Plain Horner at the critical points already beats forming D by 2.5x, but only the compensated evaluation
is exact. Both ingredients are needed.

## End-to-end effect: honest accounting

| shape | lost (before -> after) | spurious | roots within 1 ulp |
|---|---|---|---|
| Marlin xPoints | 0 -> 0 | 0 -> 0 | 100.00% -> 100.00% |
| Marlin perpendiculardfddf | 0 -> 0 | 0 -> 0 | 100.00% -> 100.00% |
| 3 roots in [A,B) | 0 -> 0 | 0 -> 0 | 100.00% -> 100.00% |
| wild, 20 decades | 2827 -> 2816 | 1381 -> 1302 | 88.72% -> 88.62% |

On Marlin-shaped input this changes nothing measurable: the branch was already right there and the roots
were already correctly rounded by the compensated-Horner Newton step. On 20-decade coefficients it is
marginal too -- those 2816 remaining lost roots are not lost in the branch decision but in the
normalisation (a /= d) and in the root formulas, before Newton can pull them back inside the 1e-6
matching window.

So this is a robustness change, not a measured win on realistic curves: it converts a branch decision
that is 9.5% wrong at 20 decades and 15-26% wrong on near-double roots into one that is exact, at a cost
of one quadratic discriminant plus two compensated Horner evaluations per cubic -- negligible next to the
cbrt and acos/cos the branches themselves execute.

Verified: sun.java2d.marlin compiles clean; no NaN or infinite root over 2e6 polynomials at 20 decades
and 1e6 at 30 decades, including exact double and triple roots and the d == 0 path. The trigonometric
branch now also guards p < 0 and clamps the acos argument, both of which were reachable in principle
when the computed p or cb_p disagreed in sign with the exact discriminant.
