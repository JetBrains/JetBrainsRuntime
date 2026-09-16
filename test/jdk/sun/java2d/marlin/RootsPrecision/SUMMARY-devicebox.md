# The real domain: curves clipped to device space

x in [-100, 4200], y in [-100, 3000], coordinates quantised to 1/256, accuracy wanted to 1/256 px
= 3.906e-3. 200000 curves per shape, reference roots from the converged bracket run, position error
measured directly as |C(t) - C(t*)| in pixels.

## Is it faster in this domain? Yes

| shape | solver | missed | max px err | roots over 1/256 px | ns/solve | ratio |
|---|---|---:|---:|---:|---:|---:|
| perpendiculardfddf | original | 53 of 310544 | **78.7** | **219** | 84 | - |
| perpendiculardfddf | shipped hybrid | **0** | **0** | **0** | 162 | **1.9x** |
| xPoints | original | 0 of 182661 | 1.49e-3 | 0 | 104 | - |
| xPoints | shipped hybrid | **0** | **0** | **0** | 171 | **1.6x** |

1.6x to 1.9x, not the 3.6x measured on unclipped [0,4096] curves and nowhere near the 7.6x of the
bracket-everything version. Clipping to device space is what makes the closed form verify almost always,
so the fallback to bracketing essentially never runs. Quantising the coordinates to 1/256 changes
nothing material: 95 and 200 ns against 84 and 162 unquantised, and identical error columns.

## The two callers are not in the same position

**perpendiculardfddf, which findSubdivPoints reaches through rootsOfROCMinusW, is genuinely broken in
device space.** 219 of 310544 ROC-cusp subdivision points -- one in 1400 -- land further than 1/256 px
from where they belong, the worst by 78.7 px, and 53 roots are missed outright. That is a visible defect
on ordinary clipped input, not a pathology of synthetic coefficients.

**xPoints is already adequate.** Nothing missed, nothing over 1/256 px, worst case 1.49e-3 px against a
tolerance of 3.906e-3. The new solver buys it only exactness, at 1.6x.

The margin there is thin though: 1.49e-3 is a factor of 2.6 inside the tolerance and is an outlier, with
p99.99 at 3.7e-6. A different sample could cross it. Since the hybrid costs 1.6x rather than 3.6x on this
input, keeping one solver for both is simpler than maintaining a split for a factor that small, and it
removes the thin margin.

## On making parts of this conditional on a quality rendering hint

The measurements argue against it. The expensive part -- falling back to bracketing -- already costs
nothing on this input, because it never fires: the gate is dynamic, not a setting. What remains is the
closed form plus the verification scan, and 1.6x to 1.9x on one routine inside findSubdivPoints is not
the kind of cost a hint is normally spent on. A hint would also double the code paths to test, and the
two bugs this work uncovered were both found by scoring the shipped code against a reference rather than
by reading it.

The one piece worth considering for a hint is the opposite of what it looks like: the quadratic solver's
Newton step. quadraticRoots is now correctly rounded rather than within 2 ulps, which no Marlin geometry
needs at 1/256 px, and it costs one compensated Horner evaluation per root. Removing it under a "speed"
hint would be measurable and safe; the Kahan discriminant it sits on should stay either way, since that
is what stops the error growing as 1/gap for nearly equal roots.
