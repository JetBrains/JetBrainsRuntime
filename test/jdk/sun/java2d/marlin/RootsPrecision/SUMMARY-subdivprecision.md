# Precision in t needed by findSubdivPoints at 1e-3 px

## The requirement

A subdivision point t splits the curve at C(t); an error dt moves that point by |C'(t)| * dt to first
order, so the requirement is dt <= tol / |C'(t)|. At a turning point the error in that one coordinate is
second order, since x'(t) = 0 there, but the other coordinate still moves at first order, so the speed
|C'| = hypot(x', y') is the governing quantity.

Measured over the four kinds of point findSubdivPoints actually produces (SubdivPrecision.java, 200000
cubic Beziers, control points uniform in [0,4096]^2, tol = 1e-3 px, 870937 subdivision points):

| point kind | count | median dt | p1 | p0.1 | worst |
|---|---:|---:|---:|---:|---:|
| dxRoots / dyRoots (turning points) | 422415 | 5.9e-7 | 1.3e-7 | 9.9e-8 | 8.4e-8 |
| infPoints (inflections) | 141388 | 3.8e-7 | 1.1e-7 | 8.5e-8 | 6.7e-8 |
| perpendiculardfddf (ROC cusps) | 307134 | 6.5e-7 | 1.9e-7 | 1.6e-7 | 1.4e-7 |
| all subdivision points | 870937 | 5.6e-7 | 1.3e-7 | 9.7e-8 | **6.7e-8** |

**dt ~ 7e-8 is the answer.** Precisely: 1e-7 is just barely insufficient -- 1268 of 870937 points
(0.15%) require tighter, by at most a factor of 1.5 -- and 1e-8 covers every point measured. Nothing
needs 1e-9.

The requirement scales as dt ~ 2.3e-4 / span, since the speed is proportional to the coordinate span.
At a 1e6 span the worst case is 2.8e-10 and a precision of 1e-9 leaves 11% of points under-resolved.

## Coordinate span is irrelevant; mixed magnitudes are not

The 4096 and 1e6 spans give bit-identical root sets and identical cap requirements. Marlin's cubics are
homogeneous in the coordinate scale: scaling every control point by k multiplies all four coefficients of
perpendiculardfddf by k^2 and all four of xPoints by k, and a common factor does not move the roots. So a
uniformly large or small drawing costs nothing in conditioning.

What breaks that is a curve whose OWN control points span many decades -- mixing 1e-7 with 1e30 within
one curve -- which is what the earlier "coords 1e-7..1e30" shape did. That shape lost 1132 of 9246 roots
at 1e-7 with the original solver, while the same shape at uniform pixel coordinates loses only 16 of
18571. The conditioning follows the spread inside a curve, not the size of the drawing.

## Cap required, and what it costs

Smallest cap losing nothing, for the cubic findSubdivPoints solves through rootsOfROCMinusW, at pixel
coordinates (CapPixel.java, bounds T_A = 1e-4 and T_B = 1 - 1e-4 as Helpers uses):

| precision in t | perpendiculardfddf | xPoints |
|---|---|---|
| 1e-3 | cap 10, 324 ns (5.8x) | cap 8, 204 ns (3.0x) |
| **1e-7** | **cap 12, 350 ns (6.3x)** | **cap 10, 220 ns (3.2x)** |
| **1e-8** | **cap 12, 350 ns (6.3x)** | **cap 12, 235 ns (3.5x)** |
| 1e-9 | cap 12, 350 ns (6.3x) | cap 12, 235 ns (3.5x) |
| 1e-16 (correctly rounded) | cap 14, 371 ns (6.6x) | cap 12, 235 ns (3.5x) |

**For findSubdivPoints in pixel coordinates at 1e-3 px, cap 12 is sufficient** -- and cap 14 already buys
correct rounding, so there is little reason to tune below it. Against the 90 shipped today that is a
large saving; against the original closed form it is 3.5x to 6.6x.

## The honest comparison at this operating point

At pixel coordinates and the precision this question asks about, the original solver was very nearly
adequate: it loses 16 of 18571 roots for perpendiculardfddf (0.086%) and 0 of 11028 for xPoints, at every
tolerance from 1e-3 to 1e-9. It only collapses at 1e-16, losing 75%, which is correct rounding rather
than anything Marlin's geometry needs.

So the value of the new solver at 1e-3 px in pixel coordinates is narrow: 0.086% of ROC-cusp roots, plus
correct rounding. Its real value is the case this whole investigation started from -- curves whose
control points span many decades, where the original loses 12% at 1e-7 and up to 91% at wider spreads.
Anyone weighing the 3.5x to 6.6x should weigh it against that, not against the pixel-coordinate numbers.
