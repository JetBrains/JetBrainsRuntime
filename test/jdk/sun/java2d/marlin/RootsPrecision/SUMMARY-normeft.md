# Error-free normalisation: what it fixes and what it does not

Hypothesis tested: normalising with an error-free transform, keeping the exact division residual,
should fix the cubic solver.

    ah = fl(a/d),   r = fma(-ah, d, a) == a - ah*d exactly,   a/d == ah + r/d

so (ah, r/d) is a double-double for a/d, and p, q, sub, p^3 and D can be carried in double-double from
there, removing the ~1e-16 relative error they currently inherit from the three plain divisions.
Implemented in NormEFT.java. Two things could benefit, and they behave completely differently.

## Confirmed: it does fix the sign of D computed from p and q

Wrong signs out of 20000 random cubics, against a 120-digit evaluation of the exact discriminant:

| sign(D) method | 12 decades | 20 decades | 30 decades |
|---|---:|---:|---:|
| form D = q^2 + p^3, plain normalisation | 784 | 1884 | 2611 |
| form D = q^2 + p^3, error-free normalisation | **4** | **605** | **1663** |
| sign(f(t1)*f(t2)) at the critical points (committed) | **0** | **0** | **0** |

This validates the diagnosis from the factored-sign experiment: the 1e-16 error that p and q inherit from
a /= d really was the dominant term, and removing it takes 784 wrong signs down to 4 at 12 decades.
It does not reach zero, because past that the cancellation in q^2 + p^3 itself exceeds what
double-double holds -- at 20 decades q^2 reaches 1e60 while the true sum can be 1e20.

But the committed solver no longer computes the sign from p and q at all: it evaluates f at the critical
points of the original cubic, which never pays the normalisation error in the first place and is exact at
every range measured. So this improvement has nothing left to improve in the current design.

## Not confirmed: it does not recover the lost roots

40000 polynomials per shape, scored against exact roots:

| shape | variant | lost | spurious | <=0.5 ulp |
|---|---|---:|---:|---:|
| wild, 20 decades | committed | 2816 | 1302 | 88.20% |
| wild, 20 decades | dd normalisation | 2786 | 1182 | 88.33% |
| wild, 20 decades | dd normalisation + dd reconstruction | 2784 | 1181 | 88.30% |
| wild, 30 decades | committed | 2408 | 1082 | 91.41% |
| wild, 30 decades | dd normalisation | 2383 | 998 | 91.37% |
| Marlin xPoints | all three | 0 | 0 | ~100% |
| Marlin perpendiculardfddf | all three | 0 | 0 | 100% |

A 1% improvement on pathological input and nothing at all on Marlin-shaped curves, for roughly 30
double-double operations per cubic solve in a hot path. Not applied.

## Where the lost roots actually die

The reconstruction is root = t*cos(phi) - sub with sub = a/(3d). Measuring |sub|/|root| -- the number of
digits that subtraction has to cancel away -- separates the two populations completely:

| | p10 | median | p90 | p99 | max |
|---|---|---|---|---|---|
| lost roots | 3.0e8 | 3.4e12 | 1.5e18 | 7.2e21 | 7.3e24 |
| kept roots | 1.3e-12 | 0.016 | 7.1e5 | 7.2e10 | 1.7e16 |

The medians differ by 2e14. 21.2% of the lost roots require more than 16 digits of cancellation, against
0.02% of the kept ones.

That subtraction cannot be fixed by more precision in p and q, because cos(phi) and cbrt are themselves
only accurate to about 1e-16 relative, and that error is multiplied by t, which is of the same order as
sub. Carrying the reconstruction in double-double confirms this directly: it moved lost roots from 2786
to 2784, because the limit is the accuracy of the transcendental, not of the arithmetic around it.

Recovering those roots needs a different algorithm rather than more precision, for instance solving the
reversed polynomial c*s^3 + b*s^2 + a*s + d for s = 1/t when the wanted root is far smaller than the
shift (reciprocal roots turn the small-root cancellation into a benign large-root case), or replacing the
closed form with bisection on a bracketing interval once the branch is known. Both are larger changes
than anything here and only matter for coefficient ranges Marlin does not produce: on the actual xPoints
and perpendiculardfddf shapes the solver already loses nothing and returns correctly rounded roots.

No code change from this experiment. NormEFT.java and WhyLost.java are kept as the record.
