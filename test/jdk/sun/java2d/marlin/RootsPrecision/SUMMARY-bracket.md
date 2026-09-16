# The cubic solver, fixed for 40-decade coefficients

Target: independent coefficients a,b,c,d of either sign spanning up to 40 decades, including magnitudes
down to ~1e-15, plus Marlin Bezier coordinates from 1e-7 to 1e30.

## Result

Scored against 60-digit reference roots, 20000 polynomials per shape, methods extracted verbatim from
the shipped Helpers.java:

| shape | lost | spurious | roots within 0.5 ulp | NaN |
|---|---:|---:|---:|---:|
| coefficients over 40 decades | **0** | **0** | **100.00%** | 0 |
| over 30 decades | **0** | **0** | **100.00%** | 0 |
| over 20 decades | **0** | **0** | **100.00%** | 0 |
| all coefficients ~1e-15..1e-5 | **0** | **0** | **100.00%** | 0 |
| 3 roots in [A,B), d over 40 decades | **0** | **0** | **100.00%** | 0 |
| perpendiculardfddf, coords 1e-7..1e30 | **0** | **0** | **100.00%** | 0 |
| xPoints, coords 0..4096 | **0** | **0** | **100.00%** | 0 |
| close pair, gap 1e-6 | **0** | **0** | **100.00%** | 0 |

Previously, on the same shapes and seeds: 1087 of 3896 roots lost at 40 decades, 1317 of 4821 at 30,
1626 of 6292 at 20, 874 of 8164 for the tiny-coefficient shape, 12 of 30188 on perpendiculardfddf.

A median of 0.25 ulp with a maximum of 0.50 is what correctly rounded output means: the returned double
is the nearest one to the exact root.

## Why the closed form had to go rather than be improved

Cardano's and the trigonometric formulas both reduce to the depressed cubic, subtracting sub = a/(3d),
the mean of the three roots. A root far below that mean is then reconstructed by cancelling |sub|/|root|
digits away. Measured over the roots the closed form dropped: median |sub|/|root| = 3.4e12 against 0.016
for the roots it kept, with 21.2% of lost roots needing more than 16 digits of cancellation against 0.02%
of kept ones.

That subtraction cannot be rescued by precision, because cos(phi) and cbrt are themselves accurate only
to ~1e-16 relative and that error is multiplied by a factor the size of sub. Everything tried against it
recovered only a fraction:

| attempt | result |
|---|---|
| fma + TwoSum on p, q, p^3, D | accuracy better, lost roots bit-for-bit unchanged |
| relative degeneracy tolerance | fixed one failure mode, created the mirror-image one |
| error-free normalisation, division residual kept in double-double | lost 2816 -> 2786 of 12471 |
| double-double root reconstruction as well | 2786 -> 2784 |
| difference-of-squares sign test | bit-identical wrong-sign counts to forming D |
| Blinn's reciprocal direction | 60 -> 18 lost on perpendiculardfddf, a quarter still lost at 20 decades |

## What works

The SIGN of f is reliable at every scale, from the compensated Horner scheme. f is monotonic between its
critical points, so:

1. Split [A,B) at the critical points of f, from the Kahan-discriminant quadratic solve: at most three
   monotonic pieces.
2. A piece whose endpoint values have opposite signs contains exactly one root. Locating it needs nothing
   but correct signs.
3. Newton safeguarded by the bracket converges to it, seeded by false position from the two endpoint
   values already in hand, falling back to bisection whenever a step would leave the bracket. The point
   with the smallest |f| seen is returned, which for a simple root is the correctly rounded double.
4. A root of even multiplicity sits at a critical point and changes no sign, so those are picked up by
   testing f at the critical points -- accepting only an EXACT zero, since a critical point where f is
   merely small is a near-tangency and the polynomial as given has either two simple roots there, already
   found by step 3, or none. Accepting near-zeros instead cost 67 spurious roots per 12105 on
   perpendiculardfddf.

Two implementation details that cost accuracy when wrong, both found by measurement:

- The Newton step must be computed BEFORE the bracket is narrowed. Narrowing first rejects a step back
  towards the root as out of bounds and forces a midpoint jump, which threw away all the accuracy of the
  seed: 100% within 0.5 ulp collapsed to 0.00% on a shape where nothing was lost.
- Returning the last iterate rather than the best-residual point has the same effect.

## The closed form is gone entirely, and that is faster

Once the bracket decides the answer, the closed form is only a seed, so its cost is negotiable. Measured
on Marlin pixel-coordinate xPoints, 1e6 solves, ns per solve:

| seeding | ns/solve | correctness |
|---|---:|---|
| closed form both directions + bracket | 407 | 0 lost, 100% |
| closed form forward only + bracket | 331 | 0 lost, 100% |
| bracket midpoint, no closed form | 292 | 0 lost, 100% |
| **false position, no closed form** | **290** | **0 lost, 100%** |
| fma closed form alone (previous commit) | 114 | loses a quarter at 20 decades |
| original Marlin closed form | 66 | loses a quarter, plus 6.1% wrong branch |

The closed form costs more than the bisection iterations it saves, because cbrt, acos and two cos calls
dominate. Dropping it removed 283 lines from Helpers.java, including cubicRootsRaw, discriminantSign and
the whole Cardano and trigonometric apparatus.

## The cost, stated plainly

290 ns/solve against 66 for the original: **4.4x**. That is the honest price of never losing a root. It
is unmeasured at the rendering level -- a JMH pass on Stroker and Dasher is still needed, and how much of
a real frame is spent in cubicRootsInAB is unknown.

If that proves too expensive, the natural mitigation is a fast-path gate: the closed form is safe when
the shift is small, so a single test such as |a| <= K * |d| could pick the 114 ns path and leave the
bracket for the rest. That reintroduces a magic constant of exactly the kind that caused the original
6.1% lost-root bug, so it is deliberately not done here; it should be a measured decision, not a guess.

## Verification

- sun.java2d.marlin compiles clean.
- The scored methods are extracted verbatim from the shipped Helpers.java, so the table above describes
  the committed code. This check has now caught two bugs that the prototypes hid.
- 3e6 polynomials covering exact zero coefficients, subnormals, 1e+-300, 1e-15, 40 decades, and exact
  double and triple roots: no root out of [A,B), none non-finite, no exception. 1163 of 1124443 returned
  roots (0.10%) exceed a strict residual test at 65536 ulp of the largest evaluation term; those are
  near-multiple roots where f is flat, and the returned value is still the best double in a bracket that
  provably contains a root.
- No jtreg run: needs a full JDK build this workspace cannot do.

## Follow-up: the ranges, and where false position belongs

The ranges tested ARE the monotonic pieces between the roots of f', so at most three, which is what
criticalPoints() plus the bnd[] array builds: A, the critical points falling inside (A,B), then B.
Measured on Marlin pixel-coordinate xPoints, the average is 2.05 ranges per solve.

Within a range, false position was measured in three roles (IterVariants.java, 1e6 solves for timing,
12000 polynomials per shape for correctness):

| iteration scheme | ns/solve | iterations/root | 40 decades | perpendiculardfddf 1e30 |
|---|---:|---:|---|---|
| **Newton + midpoint safeguard, false-position seed (shipped)** | **283** | **37.5** | **0 lost, 100%** | **0 lost, 100%** |
| pure regula falsi + midpoint safeguard | 445 | 46.4 | 1566 lost, 68.50% | 3387 lost, 80.50% |
| Illinois (retained endpoint halved) | 607 | 51.9 | 0 lost, 100% | 0 lost, 100% |
| Newton, falling back to false position | 290 | 33.1 | 457 lost, 99.16% | 629 lost, 99.95% |

False position is the right SEED -- it costs nothing, since both endpoint values are already in hand --
but the wrong ITERATION. Pure regula falsi stalls: one endpoint is never replaced, so the bracket stops
shrinking and the exhaustion test fires before convergence, losing roots outright. Illinois fixes the
stalling and is correct, but needs more iterations than Newton and is twice the cost. Using false
position merely as Newton's fallback inherits the same stalling and loses 457 roots.

## Why 37 iterations per root, and what does not fix it

The iteration histogram is bimodal: a fast population at 3-30 and a second peak at 55-57. Three
hypotheses were tested and all three were wrong:

| attempted fix | result |
|---|---|
| step-size convergence exit, break when \|nt - t\| <= ulp(t) | 36.4 iterations, 291 ns: no gain |
| geometric bisection fallback while hi > 2*lo (the bracket spans decades) | 37.5 iterations, 293 ns: no gain |
| break when the bracket reaches adjacent doubles | 305 vs 310 ns: inside noise |

So the iterations are not an exhaustion artifact and not a decade-descent problem. They are Newton
converging LINEARLY, which is what happens when the root sits near a critical point -- f' is small there,
so the error halves per step instead of squaring, and reaching ulp level from 1e-3 takes about 43 steps.
Around half of these cubics have a root near a critical point. That is inherent to the root, not to the
safeguard.

## The one saving on the table, not taken

The iteration cap is a direct cost/accuracy knob, because the smallest-|f| point is tracked and returned:

| cap | ns/solve | px lost | 40 decades lost | perpendiculardfddf lost |
|---:|---:|---:|---:|---:|
| 90 (shipped) | 287 | 0 | 0 | 0 |
| 40 | 249 | 0 | 0 | 0 |
| 20 | 199 | 0 | 177 | 337 |
| 12 | 168 | 0 | 476 | 916 |
| 8 | 149 | 14 | 837 | 1398 |

A cap of 40 is 13% faster with no loss measured over 8000 polynomials per shape. It is not applied: the
saving is small, the evidence thinner than for the rest of this work, and a truncation constant chosen
against a sample is exactly the kind of tuned threshold whose failure mode started this whole
investigation. It is a one-token change in solveBracket if the 13% is wanted.
