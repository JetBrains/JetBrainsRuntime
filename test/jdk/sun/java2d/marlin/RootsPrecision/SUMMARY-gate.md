# Cap on a larger sample, a free convergence exit, and why no cheap gate works

## The cap, on three times the data

60000 polynomials of 20-decade coefficients, 18922 roots in [A,B), against the 6066 used before:

| cap | lost | iterations/root | ns/solve | with step exit: lost / iters / ns |
|---:|---:|---:|---:|---|
| 12 | 4247 | 11.8 | 125 | 4247 / 10.6 / 120 |
| 16 | 2359 | 15.4 | 140 | 2359 / 13.1 / 131 |
| 20 | 1028 | 18.9 | 152 | 1028 / 15.3 / 140 |
| 24 | 340 | 22.0 | 162 | 340 / 17.3 / 144 |
| 28 | 81 | 24.9 | 166 | 81 / 19.1 / 148 |
| 32 | 2 | 27.4 | 173 | 2 / 20.6 / 152 |
| **40** | **0** | 31.7 | 185 | **0** / 22.9 / 158 |
| 48 | 0 | 35.4 | 193 | 0 / 24.8 / 163 |

The original closed form on the same sample loses 10230 of 18922 roots (54%) at 65 ns.

A cap of 20 to 30 does not hold: 20 loses 1028 roots and 28 loses 81. The boundary is 40, now confirmed
on three times the earlier sample, and the shipped 48 is that boundary plus a step. The earlier 6066-root
sample that suggested 26 was simply too small to see a loss rate of a few per thousand.

## A free convergence exit: 27% faster

Newton's own step bounds the error still to be removed, so stopping when |nt - t| <= 1e-11 * |t| stops at
a stated precision rather than grinding to the cap. Loss counts are IDENTICAL at every cap with and
without it, and iterations fall from 35.4 to 24.8 per root.

On device-scale perpendiculardfddf the whole solve goes from 624 to 454 ns, so 7.8x the original instead
of 11.1x. Position error at device scale stays identically zero at spans 4096 and 32768 over 38606 roots,
so 1/512 px is still met exactly. What it gives up is bit-exactness in two of the eight shapes: roots
within 0.5 ulp drop from 100% to 99.99% on perpendiculardfddf at 1e-7..1e30 and to 98.81% on a close pair
at gap 1e-6. The bound is 1e-11 relative, four orders inside the 1.3e-7 that 1/512 px requires.

## Why no cheap gate separates the safe cases -- two candidates, both measured and rejected

The idea was to run the closed form when the coefficients are well formed and the bracket solver only
otherwise. The closed form used for this test is the best available one: fma-compensated p, q, p^3 and D,
the exact critical-point discriminant sign, and a compensated-Horner Newton step.

Gate on the shift |sub| = |a/(3d)|, which is what the reconstruction cancels against:

| \|sub\| bucket | device scale (both cubics) | 20-decade coefficients | perp, coords 1e-7..1e30 |
|---|---|---|---|
| < 1 | 0 lost of 37165 and 0 of 17660 | 0 lost | **25 lost of 35905** |
| < 10 | 0 | 0 | **18 more** |
| < 1e3 | 0 | 0 | 0 |
| < 1e4 | - | 44 lost (15.5%) | - |
| >= 1e9 | - | 973 lost (97.6%) | - |

Gate on the normalised coefficient size max(|a/d|, |b/d|, |c/d|):

| bucket | device scale | 20-decade | perp 1e-7..1e30 |
|---|---|---|---|
| < 1 | 0 lost | 0 | 0 |
| < 10 | 0 lost of 32829 and 0 of 18203 | 0 | **43 lost of 27484** |
| < 1e3 | 0 | 0 | 0 |
| >= 1e3 | - | 1070 lost (74.4%) | - |

Both gates fail the same way: the bucket that is perfectly safe at device scale -- zero lost roots over
55000 roots -- is the bucket where the mixed-coordinate curves lose theirs. The two cases are
indistinguishable from the coefficients alone, because what makes the second unsafe is the root
configuration, a near-degenerate discriminant, and not the magnitude of any coefficient. Predicting it
requires doing the work the gate was meant to avoid.

## The gate that would work is at the caller, not in the solver

Across 50000 device-scale curves and 55000 roots the closed form loses nothing at all, for both cubics.
The property that makes them safe is that the four control points are of comparable magnitude -- which
the solver cannot see, since it only receives four coefficients, but Curve and Stroker can.

So a working fast path has to be gated where the coordinates are still visible: test the control-point
spread once per curve, then pick the solver. That needs a flag threaded from Curve through
rootsOfROCMinusW and perpendiculardfddf into cubicRootsInAB, plus keeping the closed form alongside the
bracket solver -- two implementations of the same routine, which is what the extraction check kept
catching bugs in. It is a real option and it is measured to be safe for device-scale input, but it is a
larger change than tuning a constant, so it is not done here.
