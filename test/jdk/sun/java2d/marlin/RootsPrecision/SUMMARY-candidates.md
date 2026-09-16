# Two candidate simplifications, measured on device-clipped input

Coordinates quantised to 1/256 in x [-100,4200], y [-100,3000]; tolerance 1/256 px = 3.906e-3.
200000 curves per candidate. Position error measured as |C(t) - C(t*)| in pixels.

## Candidate A: put xPoints / yPoints back on the plain closed form -- NOT recommended

findClipPoints solves x(t) = clip edge and y(t) = clip edge over [0,1), with the clip rect at
0..4096 by 0..3000 and curves crossing it.

| solver | roots | missed | median px | max px | over 1/256 | ns/solve |
|---|---:|---:|---:|---:|---:|---:|
| plain closed form | 202988 | **4** | 1.29e-12 | **1.87e-2** | **3** | **79** |
| shipped hybrid | 202988 | 0 | 0 | 0 | 0 | 223 |

It saves 2.8x, and it loses clip intersections: 4 missed and 3 more beyond 1/256 px out of 202988,
roughly one in 50000 curves. A missed intersection means the curve is not split at the clip boundary.

This corrects an earlier conclusion of mine. I had measured xPoints with the target x0 drawn randomly
inside the curve's own coordinate range and found the plain solver adequate -- 0 missed, worst 1.49e-3 px.
The real distribution is harder: the target is a CLIP EDGE, and a curve grazing that edge gives
x(t) - edge a near-double root, which is exactly the ill-conditioned case. Testing the real target
distribution rather than a plausible-looking one changes the answer.

## Candidate B: drop the Newton step from quadraticRoots -- recommended

findSubdivPoints' three quadratics per curve: dxRoots, dyRoots and infPoints.

| solver | roots | missed | median px | max px | over 1/256 | ns per 3 quadratics |
|---|---:|---:|---:|---:|---:|---:|
| Kahan discriminant only | 564091 | 0 | 0 | **2.06e-12** | 0 | **42** |
| shipped, Kahan + Newton | 564091 | 0 | 0 | 0 | 0 | 89 |

2.1x on the quadratic path, and the worst position error is 2.06e-12 px against a budget of 3.906e-3 --
nine orders of margin, nothing missed, nothing over tolerance. The Newton step buys correct rounding,
which no Marlin geometry needs; the Kahan discriminant beneath it is what matters and stays.

Across the whole root-finding of findSubdivPoints, three quadratics plus one ROC-cusp cubic:

| configuration | quadratics | cubic | total per curve |
|---|---:|---:|---:|
| shipped | 89 | 162 | 251 ns |
| with candidate B | 42 | 162 | **204 ns** |
| original code | ~40 | 84 | ~124 ns |

Candidate B takes the whole path from 2.0x the original to 1.65x, for error nine orders inside the
tolerance.

## Recommendation

Take B, leave A. B is free in accuracy terms and cuts a fifth off the path. A buys 2.8x on the clip path
at the price of occasionally not splitting a curve at the clip boundary, which is the class of defect
this whole exercise set out to remove.

If a speed hint is still wanted, B is the change to put behind it -- but since it costs nothing
measurable at 1/256 px, it is simpler to take it unconditionally than to maintain two paths.
