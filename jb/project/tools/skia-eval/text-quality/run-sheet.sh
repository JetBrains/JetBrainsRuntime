#!/bin/bash
# Text sheet for one arm at one scale (plan: build/notes/text-quality-matrix-plan-2026-10-20.md).
#   run-sheet.sh <arm> <scale> [out-dir]      arms: A0 A1 A2 A3 R1 (see below)
# Writes <out-dir>/<arm-name>/sheet-<scale>x.png, the software baseline and the manifest, then the
# statistics, heat map, crops and index via TextSheetDiff. Compile first with compile.sh.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-$HERE/../windows-aarch64-skia/jdk}"
ARM="$1"; SCALE="${2:-1}"; OUT="${3:-$HERE/out/$(date +%F)}"
EXPORTS="--add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.awt.image=ALL-UNNAMED"
COMMON="-Dsun.java2d.uiScale=1 -Dsun.java2d.d3d=false"
case "$ARM" in
  A0) NAME=A0-software;   PROPS="-Dsun.java2d.skia=false" ;;
  A1) NAME=A1-blend;      PROPS="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend" ;;
  A2) NAME=A2-twopass;    PROPS="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass" ;;
  A3) NAME=A3-accumulate; PROPS="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate" ;;
  R1) NAME=R1-d3d9;       PROPS="-Dsun.java2d.skia=false -Dsun.java2d.d3d=true"; COMMON="-Dsun.java2d.uiScale=1" ;;
  *)  echo "unknown arm $ARM" >&2; exit 2 ;;
esac
mkdir -p "$OUT"
"$JDK/bin/java" $EXPORTS $COMMON $PROPS ${EXTRA_PROPS:-} -cp "$HERE/out-classes" TextSheet --arm "$NAME" --scale "$SCALE" --out "$OUT"
"$JDK/bin/java" -cp "$HERE/out-classes" TextSheetDiff "$OUT/$NAME" "${SCALE}x" ${DIFF_ARGS:-}
