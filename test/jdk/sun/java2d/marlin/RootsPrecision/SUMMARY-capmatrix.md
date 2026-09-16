# Best iteration cap by decade range and required precision

Question: what cap gives the best performance at a stated precision, judged against the original
(faulty) solver? Harnesses CapMatrix.java and IterTail.java; raw output capmatrix.txt.

Setup: coefficients +/- 10^U(-D/2, D/2) for D decades of spread, plus Marlin's own two shapes
(xPoints at pixel coordinates, perpendiculardfddf with coordinates 1e-7..1e30). A true root in
[1e-6, 1-1e-6) counts as found when a returned value is within the stated absolute distance in t.
1e-16 is below ulp(1) and therefore means correctly rounded; 1e-7 is the stated Marlin coordinate
precision; 1e-3 is a coarse subdivision tolerance. 6000 polynomials per shape, exact roots from the
60-digit reference; each row times the original and the capped solver on the SAME sample, so the
ratio is meaningful even though absolute ns depends on how many roots the sample has per polynomial.

## Smallest cap that loses nothing

| shape | 1e-3 | 1e-7 | 1e-16 |
|---|---:|---:|---:|
| 3 decades | 12 | 12 | 16 |
| 10 decades | 20 | 24 | 24 |
| 15 decades | 20 | 28 | 32 |
| 30 decades | 20 | 36 | 40 |
| 50 decades | 20 | 36 | 40 |
| xPoints, pixel coords | 12 | 12 | 16 |
| perpendiculardfddf, 1e-7..1e30 | 20 | 36 | 40 |

## Cost at that cap, against the original on the same sample

| shape | original ns | 1e-3 | 1e-7 | 1e-16 |
|---|---:|---|---|---|
| 3 decades | 56 | 131 (2.3x) | 131 (2.3x) | 143 (2.6x) |
| 10 decades | 60 | 161 (2.7x) | 172 (2.9x) | 172 (2.9x) |
| 15 decades | 53 | 153 (2.9x) | 172 (3.2x) | 179 (3.4x) |
| 30 decades | 52 | 119 (2.3x) | 145 (2.8x) | 150 (2.9x) |
| 50 decades | 38 | 97 (2.6x) | 114 (3.0x) | 117 (3.1x) |
| xPoints, pixel coords | 60 | 229 (3.8x) | 229 (3.8x) | 254 (4.2x) |
| perpendiculardfddf | 57 | 411 (7.2x) | 531 (9.3x) | 556 (9.8x) |

## What the original actually loses, which is the point of comparison

| shape | 1e-3 | 1e-7 | 1e-16 |
|---|---:|---:|---:|
| 3 decades | 0 of 2509 | 0 | 1844 (73%) |
| 10 decades | 479 of 2437 (20%) | 755 (31%) | 2048 (84%) |
| 15 decades | 744 of 2162 (34%) | 991 (46%) | 1888 (87%) |
| 30 decades | 826 of 1484 (56%) | 958 (65%) | 1335 (90%) |
| 50 decades | 651 of 1007 (65%) | 744 (74%) | 913 (91%) |
| xPoints, pixel coords | 0 of 5426 | 0 | 4083 (75%) |
| perpendiculardfddf | 1050 of 9246 (11%) | 1132 (12%) | 6753 (73%) |

Two things stand out. At pixel coordinates and 1e-7, the ORIGINAL solver loses nothing: for xPoints
on ordinary input it was never broken, and the whole cost of the new solver buys only correct
rounding there. Where it is broken at 1e-7 is perpendiculardfddf, at 12%, and every wide coefficient
range, from 31% at 10 decades to 74% at 50.

## Recommendation

- **Correct rounding everywhere: cap 48.** Cap 40 is the largest boundary value observed (30 and 50
  decades and perpendiculardfddf all need exactly 40, with 36 losing 6, 10 and 3 roots), so 40 has no
  margin at all. 48 loses nothing on every shape and costs about 8% more than 40 and 8% less than the
  90 currently shipped.
- **If 1e-7 in t is enough: cap 36**, which loses nothing anywhere at that tolerance.
- **If 1e-3 is enough: cap 20.**
- Do not go below 12 at any tolerance: cap 8 already loses 261 roots at 3 decades and 4116 on
  perpendiculardfddf.

A floor to be aware of: at cap 4 the xPoints shape still costs 144 ns against the original's 60, so
roughly 2.4x is the fixed overhead of the approach -- criticalPoints plus the two to four compensated
Horner evaluations of the bracket scan -- and no cap recovers it. The iteration count only moves the
part above that floor.

## Iterations actually consumed, which is NOT the same question

IterTail.java, 400000 polynomials per shape, cap raised to 190 so nothing truncates:

| shape | roots | mean | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|
| 3 decades | 166732 | 38.1 | 62 | 63 | 65 |
| 10 decades | 164202 | 40.1 | 70 | 74 | 77 |
| 15 decades | 144092 | 41.5 | 76 | 81 | 86 |
| 30 decades | 97034 | 43.5 | 82 | 89 | 91 |
| 50 decades | 66463 | 44.6 | 85 | 89 | 91 |
| xPoints, pixel | 364922 | 37.5 | 60 | 61 | 67 |
| perpendiculardfddf | 606493 | 36.3 | 73 | 86 | 91 |

The loop will happily consume 91 iterations, so the cap of 90 shipped today already truncates a few
roots -- and truncating them costs nothing, because accuracy is reached by iteration 16 to 40 while
the remaining iterations are the bracket-exhaustion dance after the answer is already in `best`.
That is why the cap must be chosen from the loss curve above and not from this table: setting it to
the observed maximum would pay for 50 iterations that change no result.
