# Agent entry point for this branch

This is the shared branch of the evaluation of Skia as JBR's rendering backend (JBR-10654):
`manuel.unterhofer/JBR-10654-skia-backend`, on `origin` and on the fork `manu-unter/JetBrainsRuntime`.
The backend lives in `src/java.desktop/**/libawt_skia` and `sun.java2d.skia`; everything about the
evaluation — the records, the tooling, the procedure — lives in `jb/project/tools/skia-eval/`.

Start by reading `jb/project/tools/skia-eval/README.md` in full, then `records/ledger.md` §0, §10 and
§13, then the latest raw record of the machine you are on in `records/raw/`.

Working rules in one breath: a machine other than the integrating Mac works on
`manuel.unterhofer/JBR-10654-skia-backend-<machine>N` on the fork and never pushes to the shared
branch; every finding becomes a jtreg test that is red before the fix and green after, folded into the
guarding commit; commit messages in JBR style, no trailers; measure with n ≥ 5 interleaved runs and
report median and spread; write dated raw records to `jb/project/tools/skia-eval/records/raw/`; keep
captures and everything generated under `build/skia-eval-out/`; produce captures, not viewers or
catalogs — those are assembled and published from the Mac.
