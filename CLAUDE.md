# Agent entry point for this branch

This branch, `manuel.unterhofer/JBR-10654-skia-backend-perf-handover`, is a hand-over branch for
evaluating the experimental Skia rendering backend of JBR on this machine (Windows x64, discrete
GPU). It is the shared backend branch plus one directory and this file.

Start by reading `jb/project/tools/skia-eval/HANDOVER.md` in full: mission, the state of the work,
the user's rules (branches, tests before fixes, no co-author trailers, Robot safety, records), the
phased plan, and the pitfalls. Then `jb/project/tools/skia-eval/notes/skia-rendering-ledger-snapshot-2026-10-22.md`
§0, §10, §13 and §19.

Working rules in one breath: never push to `manuel.unterhofer/JBR-10654-skia-backend`; work on
`manuel.unterhofer/JBR-10654-skia-backend-<machine>N` on the fork `manu-unter/JetBrainsRuntime`;
every finding becomes a jtreg test that is red before the fix and green after; measure with n ≥ 5
interleaved runs and report median and spread; write dated raw records to
`jb/project/tools/skia-eval/notes/raw-<machine>-<date>.md`; keep captures under `build/skia-eval-out/`.
