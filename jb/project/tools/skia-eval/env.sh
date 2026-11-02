#!/bin/bash
# Sourced by the kit's scripts: where the repository is, where everything generated goes, which Skia image runs.
#   KIT       this directory (jb/project/tools/skia-eval)
#   REPO      the repository root
#   OUT_ROOT  generated state and artefacts: build/skia-eval-out (git-ignored; SKIA_EVAL_OUT overrides)
#   JDK       the Skia image (JDK overrides): macosx-aarch64-skia on a Mac, the windows-*-skia image elsewhere
KIT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$KIT/../../../.." && pwd)"
OUT_ROOT="${SKIA_EVAL_OUT:-$REPO/build/skia-eval-out}"
# an inherited JDK counts only if it is one (a stale export from another shell once pointed nowhere)
[ -n "${JDK:-}" ] && [ ! -x "$JDK/bin/java" ] && JDK=""
case "$(uname -s)" in
  Darwin) JDK="${JDK:-$REPO/build/macosx-aarch64-skia/images/jdk}" ;;
  *)      JDK="${JDK:-$(ls -d "$REPO"/build/windows-*-skia/images/jdk 2>/dev/null | head -1)}" ;;
esac
export KIT REPO OUT_ROOT JDK
