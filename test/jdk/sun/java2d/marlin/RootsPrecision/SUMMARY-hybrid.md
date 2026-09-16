# Fast path for well-formed coefficients: verify, do not predict

## The proposal, and why a coefficient scale cannot carry it

The idea was to compute a scale from a, b, c, d -- the sum of absolute values -- and use it to run the
closed form when it is safe, reserving the bracket solver for pathological input. The first half is
right: the closed form IS safe on real curves. Over 39456 device-scale perpendiculardfddf cubics and
29041 device-scale xPoints cubics it does not lose a single root.

The second half does not work. Five candidate discriminators were measured over the polynomials where
the closed form lost a root against those where it did not (Discrim.java, 40000 per shape):

| metric | 20-decade: LOST median / OK median | perp 1e-7..1e30: LOST median / OK median |
|---|---|---|
| S = sum of \|coef\| | 8.91e+06 / 2.22e+06 | 4.50e+55 / 1.27e+56 |
| dynamic range max/min | 2.90e+12 / 9.67e+10 | 3.00 / 1.01e+01 |
| shift \|a/(3d)\| | 1.15e+10 / 6.05e-05 | 1.00 / 5.58e-01 |
| normalised max\|coef/d\| | 4.22e+10 / 1.17e-01 | 3.00 / 1.68e+00 |
| root condition number | 1.00 / 1.00 | 8.57e+10 / 1.13e+01 |

The raw sum does not separate at all -- it is not even scale-invariant, since multiplying every
coefficient by a constant multiplies the sum and leaves the roots alone. Worse, the two failure modes
have opposite signatures. On 20-decade coefficients the failures have a huge shift and a root condition
number of 1: plain reconstruction cancellation. On perpendiculardfddf with coordinates from 1e-7 to 1e30
the failures have a shift of exactly 1, a dynamic range of exactly 3 and a normalised size of exactly 3 --
values that sit in the middle of the SAFE range for device-scale curves -- and a condition number of
1e11. No single threshold covers both, and each one measured leaks about 1% of the failures.

## What works: check the answer instead

The bracket scan is needed anyway, and it gives the exact root count: f is monotonic between its critical
points, so each piece whose endpoints differ in sign holds exactly one root. That turns prediction into
verification:

    for each bracket with a sign change:
      if exactly one closed-form candidate lies strictly inside
         and one Newton step moves it by <= 1e-11 * |t|        -> accept it
      else                                                     -> solveBracket

The Newton step bounds the distance still to travel, so acceptance rests on demonstrated convergence, not
on a guess about the input. An unforeseen input class costs a fallback rather than a wrong root.

## Measured, 40000 polynomials per shape

| shape | lost | spurious | fallback rate | hybrid | bracketing everything |
|---|---:|---:|---:|---:|---:|
| perpendiculardfddf, device scale | 0 | 0 | **0.000%** | **175 ns** | 434 ns |
| xPoints, device scale | 0 | 0 | **0.000%** | 193 ns | 286 ns |
| close root pair, gap 1e-6 | 0 | 0 | 1.87% | 261 ns | 1150 ns |
| perpendiculardfddf, 1e-7..1e30 | 0 | 0 | 1.83% | 193 ns | 372 ns |
| 20-decade coefficients | 0 | 0 | 29.28% | 189 ns | 156 ns |
| 40-decade coefficients | 0 | 0 | 31.91% | 168 ns | 121 ns |

Device-scale curves never fall back. Independent wide-range coefficients fall back on about a third of
brackets and cost a quarter more than bracketing directly -- the right way round, since Marlin does not
produce them.

## Which closed form to generate candidates with

| generator | device perp | close pair 1e-6 | 20-decade |
|---|---|---|---|
| fma-compensated (p, q, p^3, D, critical-point sign, Newton) | 0.000% fallback, 175 ns | 1.87%, 261 ns | 29.3%, 189 ns |
| the original plain closed form | 0.114%, 124 ns | **76.18%, 1019 ns** | 78.78%, 187 ns |

The plain original is faster on device-scale curves, 124 against 175 ns, but collapses on a close root
pair: three quarters of brackets fall back and the solve costs 1019 ns, four times the compensated
generator. Since near-tangency is ordinary in real paths, the compensated generator is used; it keeps
every shape between 168 and 261 ns.

## Shipped

| check | result |
|---|---|
| all eight coefficient shapes | 0 lost, 0 spurious, no NaN, 99.67% to 100% within 0.5 ulp |
| position error, spans 4096 and 32768, 38606 roots | identically 0 px |
| 2e6 pathological polynomials | no out-of-range or non-finite root, no exception |
| cost, device-scale perpendiculardfddf | 200 ns against 56 for the original, **3.6x**, from 7.6x |

The closed form is back in the file, but as a candidate generator whose errors cost a fallback rather
than a wrong root -- which is what makes two implementations of the same routine affordable here.
Correctness rests entirely on the bracket scan, and the verbatim-extraction check still scores the
methods pulled straight out of Helpers.java.
