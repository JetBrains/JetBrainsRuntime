#!/bin/bash
# The text sheets of every configuration on this machine plus the platform's own rendering, and the
# comparison viewer over them:   run.sh [out-dir]   (default out/<today>)
# Layout: <out>/<arm>/<aa>/<positioning>/<scale>/<paper>.png (paper = black-on-white | white-on-black) and, from
# run-colours.sh, <out>/<arm>/gray/<positioning>/<scale>/color-on-<paper>/<color>.png; rows.txt at the root. The viewer
# gets the arms as one dimension group and aa / positioning / scale / paper / color as the set dimensions.
# Arms: coretext[-unsmoothed|-continuous] (CoreGraphics/CoreText, ctref.swift), chrome, software (Java loops), metal-platform[-plain],
# skia-platform, the Skia scaler on each pipe as coverage or cg, Skia's text path (skia-text[-darkening|-cg]), Skia's shaper
# (skia-shaper[-darkening|-cg]) and SkParagraph (skia-paragraph[-cg]); the colour sheets from run-colours.sh.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../env.sh"
WORK="$OUT_ROOT/text-reference"; mkdir -p "$WORK"
FONTS="$JDK/lib/fonts"
OUT="${1:-$WORK/$(date +%F)}"
mkdir -p "$OUT"
swiftc -O -o "$WORK/ctref" "$HERE/ctref.swift"
rm -rf "$WORK/classes"; mkdir -p "$WORK/classes"; "$JDK/bin/javac" -d "$WORK/classes" "$HERE/TextReference.java"
"$WORK/ctref" "$OUT/coretext" "$OUT/rows.txt" "$FONTS"
"$WORK/ctref" "$OUT/coretext-unsmoothed" "$OUT/rows-unsmoothed.txt" "$FONTS" --unsmoothed; rm -f "$OUT/rows-unsmoothed.txt"
"$WORK/ctref" "$OUT/coretext-continuous" "$OUT/rows-continuous.txt" "$FONTS" --continuous; rm -f "$OUT/rows-continuous.txt"
# sheet <name> <software|volatile> [JVM options...]
sheet() {
  name=$1; how=$2; shift 2
  flag=""; [ "$how" = volatile ] && flag="--volatile"
  "$JDK/bin/java" -Dsun.java2d.uiScale=1 "$@" -cp "$WORK/classes" TextReference "$OUT/$name" "$OUT/rows-$name.txt" "$FONTS" $flag 2>&1 | grep -v "^Picked up\|^WARNING\|^pipeline"
  cmp -s "$OUT/rows-$name.txt" "$OUT/rows.txt" || echo "row order differs: $name" >&2
  rm -f "$OUT/rows-$name.txt"
}
# the arms in the text settings' terms (sun.java2d.skia.text.*; 2026-11-02): coverage = blend corrected without darkening,
# cg = blend system (CoreGraphics' masks per text luminance), darkening = blend corrected with the user's font smoothing
# the Java arms: every combination of the text settings this platform offers (arms.py; the viewer reads an
# arm's dimensions from its name) — 92 arms on macOS, about 20 s each
ARMS=$(python3 "$HERE/arms.py" macOS | cut -f1 | tr '\n' ',' | sed 's/,$//')
# in parallel: an arm is one JVM writing its own directory; SHEET_JOBS at a time (the cores minus two), each
# with its own pipeline cache (the cache's index is appended, not safe across processes)
JOBS=${SHEET_JOBS:-$(( $(sysctl -n hw.ncpu 2>/dev/null || echo 8) - 2 ))}; [ "$JOBS" -lt 1 ] && JOBS=1
echo "== $(python3 "$HERE/arms.py" macOS --count) Java arms, $JOBS at a time $(date +%H:%M:%S)"
python3 "$HERE/arms.py" macOS | {
  while IFS=$'\t' read -r name how props; do
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do sleep 0.3; done
    sheet "$name" "$how" -Dsun.java2d.skia.pipelineCache="$WORK/pipeline-cache/$name" $props &
  done
  wait
}
echo "== Java arms done $(date +%H:%M:%S)"
bash "$KIT/viewer/chrome-ref/render.sh" "$OUT" || echo "Chrome reference skipped" >&2
bash "$HERE/run-colours.sh" "$OUT"   # the coloured sheets (gray only) into the same tree
python3 "$KIT/viewer/make-viewer.py" "$OUT" --arms "coretext,coretext-continuous,coretext-unsmoothed,chrome,$ARMS,skia-scaler-cg-classes256,skia-scaler-cg-classes1" --set-dims aa,positioning,scale,paper,color --title "Text sheets $(basename "$OUT") (macOS): platform CoreText vs Java configurations"
