# Two follow-ups: the factored sign test, and the quadratic Newton step

## 1. sign(q^2 + p^3) by difference of squares -- measured, REJECTED

The algebra is right. Cancellation in D = q^2 + p^3 only happens for p < 0, since for p >= 0 both terms
are non-negative and D > 0 unless p and q both vanish. For p < 0, with P = -p:

    D = q^2 - P^3 = (|q| - P^(3/2)) * (|q| + P^(3/2))

The right factor is a sum of positives, so sign(D) = sign(|q| - P^(3/2)). Comparing magnitudes instead
of subtracting squares should halve the digits needed: if q^2 and P^3 agree to 40 digits, then |q| and
P^(3/2) agree to only 20, which double-double holds comfortably while the difference of the squares does
not. The same reasoning covers the conditional form "is q^2 > p^3" -- compare |q| against P^(3/2) and
never form either the difference or the product.

Measured against a 120-digit evaluation of the exact discriminant, 60000 random cubics per range:

| method | 6 decades | 12 decades | 20 decades | 30 decades |
|---|---:|---:|---:|---:|
| form D = q^2 + p^3 in double | 2 | 2424 | 5706 | 7908 |
| \|q\| vs P^(3/2), double | 3 | 3661 | 8511 | 11606 |
| \|q\| vs P^(3/2), double-double | 2 | **2424** | **5706** | **7908** |
| sign(f(t1)*f(t2)) at the critical points | **0** | **0** | **0** | **0** |

The double-double magnitude comparison returns bit-identical wrong counts to simply forming D -- 2424,
5706, 7908 -- at every range. That identity is the diagnosis: both are limited by the same upstream
error, not by the arithmetic that combines p and q.

p and q are computed after the normalisation a /= d; b /= d; c /= d, so each carries about 1e-16 of
relative error. A relative error eps in q puts 2*q^2*eps into q^2. When q^2 and P^3 agree to 40 digits
the true |D| is around 1e-40 * q^2, which is far below that 2e-16 * q^2 noise floor. The sign is already
destroyed before any factorisation is applied, so no way of arranging p and q can recover it -- in
double, in double-double, factored or not.

Near-double roots, 20000 cubics per gap, wrong signs:

| gap | form D | \|q\| vs P^(3/2) double | \|q\| vs P^(3/2) dd | critical points |
|---|---:|---:|---:|---:|
| 1e-6 | 0 | 0 | 0 | **0** |
| 1e-8 | 2956 | 4321 | 2956 | **0** |
| 1e-10 | 5185 | 7376 | 5185 | **0** |
| exact double root | 5025 | 7208 | 5025 | **0** |

This is exactly why the committed test works: it never forms p or q at all. It evaluates f at the
critical points of the ORIGINAL cubic (d, a, b, c), so it never pays the normalisation error, and f is
stationary at those points so the error of the critical points themselves enters only at second order.
The factorisation is the right move for the expression q^2 - P^3 in isolation; it is the wrong level of
the computation to attack here.

No code change. The committed critical-point test stands.

## 2. Quadratic solver + one Newton step -- APPLIED

Kahan's discriminant already held every root under 2 ulps. One Newton step with a compensated-Horner
residual makes them correctly rounded. N = 20000 per scenario, 40000 roots:

| scenario | Kahan only | + Newton, plain fma Horner | + Newton, compensated Horner |
|---|---|---|---|
| 2 roots uniform in [A,B) | 70.6% <=0.5 ulp, max 1.88 | 55.1%, max 2850.69 | **100.0%, max 0.50** |
| close roots gap 1e-2 | 73.6%, max 1.36 | 2.9%, max 45.48 | **100.0%, max 0.50** |
| close roots gap 1e-4 | 73.8%, max 1.37 | 0.0%, max 4465.67 | **100.0%, max 0.50** |
| close roots gap 1e-6 | 73.9%, max 1.38 | 0.0%, max 446443.42 | **100.0%, max 0.50** |
| Marlin dxRoots [0,4096] | 68.3%, max 2.01 | 73.8%, max 29.07 | **100.0%, max 0.50** |
| Marlin infPoints [0,4096] | 69.1%, max 1.87 | 70.9%, max 30.59 | **100.0%, max 0.50** |

A maximum of 0.50 ulp is correctly rounded by definition: the returned double is the nearest one to the
exact root, in every case measured.

The compensated residual is not optional. With a plain fma Horner residual the same Newton step is a
severe REGRESSION -- 446443 ulps at a root gap of 1e-6 against 1.38 for no step at all -- because f(t)
near a root is pure rounding noise and Newton divides that noise by an equally small f'. A naive Newton
polish would have made this solver far worse while looking like an improvement on the average case.

Robustness: 300000 quadratics with coefficients over 20 decades, including deliberate double roots, give
no NaN or infinite root, no lost root and no spurious root, before or after the change. The step is
skipped when f' == 0, so a double root keeps its unrefined value.
