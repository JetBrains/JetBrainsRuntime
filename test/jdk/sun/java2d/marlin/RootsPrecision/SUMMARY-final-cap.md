# Converged cap: 48, for 20-decade coefficients at 1/512 px

## Requirement

Coefficients spanning up to 20 decades (double's usable range for this problem, and what Marlin can
produce), subdivision points accurate to 1/512 px = 1.95e-3.

## Which criterion binds

Not pixel accuracy. Measured directly as |C(t) - C(t*)| on 31071 ROC-cusp roots at device scale, the
position error is identically zero from cap 16 upward and 2.3e-8 px at cap 12 -- already five orders
inside 1/512. What binds is not losing a root.

| cap | 20-decade lost | position error at device scale |
|---:|---:|---|
| 12 | 1169 of 6066 | 4.6e-5 px |
| 20 | 113 | 0 |
| 24 | 4 | 0 |
| 32 | 2 (second sample: 0) | 0 |
| **40** | **0 on all eight shapes** | **0** |
| 48 | 0 on all eight shapes | 0 |

## The sample-fitting trap, hit once

A 25000-polynomial sample of 20-decade coefficients (6066 roots) put the zero-loss boundary at 26, so 32
looked like a comfortable 1.25x margin. It was set to 32 and then failed the full suite: 27 roots lost in
total, 9 at 40 decades, 7 at 30, 2 at 20 and 9 on perpendiculardfddf with coordinates from 1e-7 to 1e30.
The 20-decade failure is the significant one -- a second sample of exactly the same width disagreed with
the first, because the loss rate near the boundary is a few roots per 6000 and two samples of that size
cannot resolve it.

The lesson, for the third time in this work: a truncation constant must be set from the boundary of the
whole suite plus a step, never from the smallest value that scored zero on one sample. Cap 40 is that
boundary, verified on eight shapes and two independently drawn 20-decade samples. 48 is the boundary plus
a step.

## Shipped: 48

| check | result |
|---|---|
| 40 decades, 30, 20, tiny 1e-15..1e-5 | 0 lost, 0 spurious, 100.00% within 0.5 ulp |
| 3 roots in [A,B) with d over 40 decades | 0 lost, 100.00% |
| perpendiculardfddf, coords 1e-7..1e30 | 0 lost, 100.00% |
| xPoints at device scale, close pair 1e-6 | 0 lost, 100.00% |
| position error at device scale | identically 0 px, so 1/512 holds with no margin consumed |
| 2e6 pathological polynomials | no root out of range, none non-finite, no exception |

Roots are bit-exact, so the pixel tolerance is met exactly rather than approached. Cost is 8% below the
90 shipped before and 8% above the 40 boundary.

## What was rejected along the way

- A residual noise-floor stop rule, which would have cut iterations from 37.8 to 6.6 per root and the
  cost from 695 to 289 ns, looked correct on the 20- and 40-decade shapes and on device-scale pixel error.
  The full suite showed it losing 273 roots on perpendiculardfddf at 1e-7..1e30 and 3 on close pairs:
  f is flat near an ill-conditioned root, so the floor is reached while t is still far out. Requiring the
  bracket to narrow as well removed the losses but cut iterations only to 34.4 and ran slower.
- Cap 32, as above.
