# Cap boundary on 1e6 samples, caps 28 to 40 step 2

## Making 1e6 affordable

60-digit BigDecimal references do not scale to a million polynomials, and they are not needed. The number
of roots comes from the sign scan over the monotone pieces of f, which is cap-independent: a low cap never
drops a root, it only returns it inaccurately. So a converged run -- cap 200 with the early exit disabled
-- is a valid reference for the VALUES.

Validated before relying on it, against the 60-digit reference:

| shape | polys | roots | lost | within 0.5 ulp |
|---|---:|---:|---:|---:|
| 20-decade coefficients | 19884 | 6136 | 0 | 100.00% |
| perpendiculardfddf, device scale | 20000 | 30892 | 0 | 100.00% |

## 20-decade coefficients: 1e6 polynomials, 312912 roots

| cap | lost | loss rate | iterations/root | ns/solve |
|---:|---:|---:|---:|---:|
| 28 | 1475 | 0.47138% | 19.13 | 149 |
| 30 | 487 | 0.15563% | 19.92 | 148 |
| 32 | 77 | 0.02461% | 20.63 | 150 |
| **34** | **0** | **0** | 21.29 | 151 |
| 36 | 0 | 0 | 21.88 | 153 |
| 38 | 0 | 0 | 22.44 | 155 |
| 40 | 0 | 0 | 22.96 | 156 |
| original closed form | 170707 | 54.55432% | - | 61 |

## Device-scale perpendiculardfddf: 1e6 curves, 1536662 roots

| cap | lost | iterations/root | ns/solve |
|---:|---:|---:|---:|
| 28 | 0 | 15.98 | 394 |
| 34 | 0 | 17.56 | 412 |
| 40 | 0 | 18.97 | 428 |
| original closed form | 1350 (0.08785%) | - | 53 |

Real curves are far easier than independent coefficients: nothing is lost from 28 up, and the cost is
dominated by the number of roots per solve (1.54 against 0.31) rather than by the iterations.

## What the sample size was doing to this number

| sample | roots | boundary it reported |
|---|---:|---:|
| first | 6066 | 26 |
| second | 18922 | 40 |
| this one | 312912 | 34 |

The loss rate near the boundary is a few per hundred thousand, so the first two samples could not resolve
it -- 26 was too low and 40 was an artifact of testing only 32 and 40 with nothing between. Shipped is 40:
the 1e6 boundary of 34 plus six, and independently the smallest cap that loses no root on any of the eight
coefficient shapes, where 32 loses 9 roots each on the 40-decade and the 1e-7..1e30 perpendiculardfddf
shapes. It replaces 48, which was the same boundary read off a smaller sample plus a step.

## Shipped state, verified on methods extracted verbatim from Helpers.java

| check | result |
|---|---|
| all eight coefficient shapes | 0 lost, 0 spurious, 0 NaN; 98.81% to 100% within 0.5 ulp |
| position error, spans 4096 and 32768, 38606 roots | identically 0 px, so 1/512 px holds exactly |
| 2e6 pathological polynomials | no out-of-range or non-finite root, no exception |
| cost, device-scale perpendiculardfddf | 427 ns against 56 for the original, 7.6x |

The 7.6x is the number to weigh. It is 11.1x without the relative-step exit and 7.6x with it, and it is
still a microbenchmark of the solver alone: no jtreg run and no JMH pass on Stroker or Dasher has been
done, so the share of a real frame spent in cubicRootsInAB is unknown.
