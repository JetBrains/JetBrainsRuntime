# Numerical precision of the Marlin polynomial root solvers

Analysis of `sun.java2d.marlin.Helpers.quadraticRoots` and `Helpers.cubicRootsInAB`, measuring the
forward error of each returned root in ulp units against a 60-digit BigDecimal reference.

These are standalone programs, NOT jtreg tests (no `@test` tag, no assertions). They print reports.

## Contents

| file | what it is |
|---|---|
| `RootsUlpEval.java` | first pass: solvers evaluated on the whole real line |
| `RootsUlpEval2.java` | main harness: solvers restricted to `t in [A,B)`, A=1e-6, B=1-1e-6, as Marlin uses them |
| `TrueAbsErr.java` | pairing-free check: distance from each true root to the nearest returned root |
| `Diag.java` | dumps p, q, D and the branch taken for individual worst cases |
| `SUMMARY.md` | findings for the unrestricted run |
| `SUMMARY-interval.md` | findings for `[1e-6, 1-1e-6)`, plus the fix comparison -- start here |
| `report.txt`, `report-interval.txt`, `report-eps1e-4.txt`, `true-abs-err.txt`, `diag.txt` | raw output |

## Running

    javac RootsUlpEval2.java TrueAbsErr.java Diag.java
    java RootsUlpEval2 20000            # default interval, eps = 1e-6
    java RootsUlpEval2 6000 1e-4        # eps = Marlin's T_ERR
    java TrueAbsErr 20000
    java Diag

The solver code is copied verbatim into each harness, so nothing here depends on java.desktop
internals and no JDK build is needed. When `Helpers.java` changes, re-copy the two methods.

`RootsUlpEval2` also contains two diagnostic variants of the cubic solver, selected by `VARIANT`:
a relative degeneracy tolerance `|D| <= EPS*max(q^2,|p^3|)`, and additionally the Cardano branch
computing `v = -p/u` instead of `-cbrt(sqrt_D + q)`. `SUMMARY-interval.md` quantifies both.
