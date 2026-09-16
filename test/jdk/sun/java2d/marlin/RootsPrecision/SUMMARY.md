# Marlin Helpers root solvers: precision in ulps

Method: solver code copied verbatim from Helpers.java; polynomials built in double from chosen roots;
reference = root of the exact double coefficients, refined by BigDecimal Newton (60 digits);
true real-root count from the exact discriminant. N = 20000 polynomials per scenario. Full data: report.txt.

| Solver / scenario                              | median | <=2 ulp | p99      | bad polys |
|------------------------------------------------|-------:|--------:|---------:|----------:|
| linear -c/b                                    | 0.25   | 100%    | 0.5      | 0%        |
| quadratic, 2 roots in [0,1)                    | 0.55   | 85.5%   | 39       | 0%        |
| quadratic, Marlin dxRoots (coords 0..4096)     | 0.38   | 99.2%   | 1.8      | 0%        |
| quadratic, Marlin infPoints                    | 0.38   | 98.7%   | 2.3      | 0%        |
| quadratic, close roots delta=1e-k              | ~1.2e(2k-3) ulps (grows as 1/delta) | | | 0% until k=8 |
| cubic, Marlin xPoints (coords 0..4096)         | 1.4    | 58%     | 2660     | 0.01%     |
| cubic, Marlin perpendiculardfddf               | 2.9    | 39%     | 129      | 0.11%     |
| cubic, 3 roots in [0,1) (trig branch)          | 5.8    | 26%     | 363      | 6.1%      |
| cubic, 3 roots in [0,0.1]                      | -      | -       | -        | 100%      |
| cubic, 1 real root in [0,1) (Cardano)          | 2.0    | 51%     | 2433     | 0.06%     |
| cubic, close pair delta=1e-3                   | 288    | 20%     | 2905     | 91.7%     |
| cubic, close pair delta<=1e-4                  | -      | -       | -        | 100%      |

"bad poly" = wrong real-root count, or a root off by more than 1 float ulp (2^29 double ulps), or a multiple root.

Findings
1. Linear: correctly rounded (<= 0.5 ulp).
2. Quadratic: ~0.4-0.55 ulp median, no misclassification. Error grows as 1/delta for close roots because
   the discriminant b*b - 4ac is computed without compensation (Kahan/fma trick would remove this).
   The c/q trick works: tiny root next to a large one stays <= 2.1 ulp.
3. Cubic, absolute EPS=1e-9 on D=q^2+p^3 (Helpers.java:164): D scales with (root magnitude)^6, so
   well-separated small roots are classified as a double root: 6.1% of random 3-root cubics in [0,1),
   100% when all roots are < 0.1 (abs t error up to 0.08). A relative tolerance
   EPS*max(q^2,|p^3|) (diagnostic in RootsUlpEval) removes this: 0.00-0.01% bad in every well-separated
   scenario with identical ulp distribution for the remaining roots. In Marlin-like use (xPoints,
   perpendiculardfddf) the rate is only 0.00-0.11%.
4. Cubic, Cardano branch (Helpers.java:188): v = -cbrt(sqrt_D + q) cancels when |p^3| << q^2;
   worst good case 4e7 ulps (1.1e-9 abs). Using v = -p/u avoids it.
5. Cubic, trig branch: median ~6 ulps, tails from the final subtraction "t*cos(phi) - sub" when a root
   is much smaller than |sub| (relative error grows, absolute error stays ~1e-16). A Newton polish
   step would bring all simple roots to ~1 ulp.
6. Multiple/near-multiple roots (delta <= 1e-6, triple root): errors ~1e10-5e8 ulps are inherent
   conditioning (sqrt(eps), cbrt(eps)), not a solver defect.
