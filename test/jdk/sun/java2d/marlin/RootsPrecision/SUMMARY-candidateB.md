# Candidate B applied: no Newton step in quadraticRoots

quadraticRoots keeps Kahan's fma discriminant and stops there. The compensated-Horner Newton step that
made its roots correctly rounded is removed, and with it the now-dead quadratic compHorner overload.

## Why

Over 200000 device-clipped curves (1/256 quantised, x [-100,4200], y [-100,3000]), the three quadratics
of findSubdivPoints -- dxRoots, dyRoots, infPoints, 564091 roots -- place their subdivision points within
2.06e-12 px of the exact position WITHOUT the step, against a tolerance of 1/256 px = 3.906e-3. Nine
orders of margin. Nothing missed, nothing over tolerance.

The step cost as much as everything else in the routine: the three solves go from 89 ns to 33 ns.

## Accuracy that remains, shipped

| scenario | <=1 ulp | <=2 ulp | median | p99 | max | lost |
|---|---:|---:|---:|---:|---:|---:|
| 2 roots uniform in [A,B) | 97.2% | 100.0% | 0.32 | 1.15 | 1.88 | 0 |
| close roots, gap 1e-2 | 98.9% | 100.0% | 0.30 | 1.01 | 1.36 | 0 |
| close roots, gap 1e-4 | 98.9% | 100.0% | 0.30 | 1.00 | 1.37 | 0 |
| close roots, gap 1e-6 | 98.9% | 100.0% | 0.30 | 1.01 | 1.38 | 0 |
| Marlin dxRoots, device scale | 95.9% | 100.0% | 0.34 | 1.25 | 2.01 | 0 |

Every root within 2 ulps at every root gap, which is Kahan's proven bound, and the maximum absolute error
in t stays at 2.23e-16 regardless of how close the roots are. That is the property worth having, and it
comes from the discriminant rather than from the refinement: the original b*b - 4ac reached 1.2e6 ulps at
a gap of 1e-7 and a median of 1.2e6 there.

## Cost of findSubdivPoints' root finding, per curve

| configuration | 3 quadratics | ROC-cusp cubic | total |
|---|---:|---:|---:|
| original code | ~40 ns | 84 ns | ~124 ns |
| before candidate B | 89 | 162 | 251 ns |
| **shipped now** | **33** | **162** | **195 ns** |

From 2.0x the original down to 1.6x.

## Candidate A was declined

Putting xPoints and yPoints back on the plain closed form saves 2.8x on the findClipPoints path, 79 ns
against 168, and loses clip intersections: 4 missed and 3 further beyond 1/256 px out of 202988 roots,
the worst off by 1.87e-2 px. A missed intersection leaves the curve unsplit at the clip boundary, which
is the defect class this work exists to remove.

## Unchanged

The cubic suite is unaffected -- quadraticRoots feeds cubicRootsInAB's d == 0 path -- and still returns
0 lost and 0 spurious on all eight coefficient shapes with 99.67% to 100% of roots within 0.5 ulp.
