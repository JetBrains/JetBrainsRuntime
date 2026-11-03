#!/bin/bash
# The text sheets of every Java configuration on this Windows machine plus the platform's own rendering
# (dwref: DirectWrite through Direct2D, and GDI):
#   run-win.sh [out-dir]   (default build/skia-eval-out/text-reference/<today>)
# Layout as on the Mac: <out>/<arm>/<aa>/<positioning>/<scale>/<paper>.png with rows.txt at the root; the ClearType
# colour sheets under <arm>/lcd/fractional/<scale>/color-on-<paper>/<colour>.png (dwref --colours, TextReference --colours).
# The Java arms are every combination of the text settings this platform offers (arms.py Windows, 2026-11-03;
# the viewer reads an arm's dimensions from its name, kitpaths.arm_from_name): the software loops and the Skia
# pipe, the platform (JDK) scaler or Skia's, Java's glyph cache or Skia's text path, Java's or Skia's layout,
# darkening, blend, decorations. The LCD glyph source (sun.java2d.font.lcdGlyphs) is the JDK's default per scaler:
# GDI's ClearType glyphs for the platform scaler, the scaler's own for Skia's. Native arms: directwrite, gdi, and
# the browser reference (Edge, render-edge.sh). No viewer here: machines deliver the tree, the Mac builds the
# viewers and the catalog.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../../env.sh"
WORK="$OUT_ROOT/text-reference"; mkdir -p "$WORK"
FONTS="$HERE/fonts"   # the file fonts copied from the image's lib/fonts (the image is rebuilt from time to time)
OUT="${1:-$WORK/$(date +%F)}"
PY="${PY:-/c/Users/manuel.unterhofer/AppData/Local/Programs/Python/Python313-arm64/python.exe}"
mkdir -p "$OUT"
[ -x "$HERE/dwref.exe" ] || cmd //c "$(cygpath -w "$HERE/build-dwref.cmd")"
rm -rf "$HERE/classes"; mkdir -p "$HERE/classes"; "$JDK/bin/javac" -d "$HERE/classes" "$HERE/TextReference.java"
"$HERE/dwref.exe" --probe > "$OUT/luminance-probe.txt"
"$HERE/dwref.exe" "$(cygpath -w "$OUT")" "$(cygpath -w "$OUT/rows.txt")" "$(cygpath -w "$FONTS")"
"$HERE/dwref.exe" "$(cygpath -w "$OUT")" "$(cygpath -w "$OUT/rows-colours.txt")" "$(cygpath -w "$FONTS")" --colours > /dev/null; rm -f "$OUT/rows-colours.txt"
# sheet <name> <software|volatile> [JVM options...]: the arm's sheets, and the colour sheets where asked (COLOURS=1)
sheet() {
  name=$1; how=$2; shift 2
  flag=""; [ "$how" = volatile ] && flag="--volatile"
  "$JDK/bin/java" -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.pipelineCache="$WORK/pipeline-cache/$name" "$@" -cp "$HERE/classes" TextReference "$OUT/$name" "$OUT/rows-$name.txt" "$FONTS" $flag 2>&1 | grep -v "^Picked up\|^WARNING\|^pipeline" || true
  diff -q --strip-trailing-cr "$OUT/rows-$name.txt" "$OUT/rows.txt" > /dev/null || echo "row order differs: $name" >&2
  rm -f "$OUT/rows-$name.txt"
  if [ "${COLOURS:-0}" = 1 ]; then
    "$JDK/bin/java" -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.pipelineCache="$WORK/pipeline-cache/$name" "$@" -cp "$HERE/classes" TextReference "$OUT/$name" "$OUT/rows-$name.txt" "$FONTS" $flag --colours 2>&1 | grep -v "^Picked up\|^WARNING\|^pipeline\|^wrote" || true
    rm -f "$OUT/rows-$name.txt"
  fi
}
# in parallel: an arm is one JVM writing its own directory, SHEET_JOBS at a time (the cores minus two), each
# with its own pipeline cache (the cache's index is appended, not safe across processes)
JOBS=${SHEET_JOBS:-$(( $(nproc 2>/dev/null || echo 8) - 2 ))}; [ "$JOBS" -lt 1 ] && JOBS=1
echo "== $("$PY" -I "$HERE/../arms.py" Windows --count) Java arms, $JOBS at a time $(date +%H:%M:%S)"
# the ClearType colour sheets for the arms where the per-channel correction is the question (hand-over 2026-10-26c):
# the platform's masks (blend system) from the Skia scaler, with the system's darkening and decorations
"$PY" -I "$HERE/../arms.py" Windows | {
  while IFS=$'\t' read -r name how props; do
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do sleep 0.3; done
    colours=0; case "$name" in *scaler-skia*blend-system+decorations-system*) case "$name" in *darkening-none*) ;; *) colours=1 ;; esac ;; esac
    COLOURS=$colours sheet "$name" "$how" $props &
  done
  wait
}
echo "== Java arms done $(date +%H:%M:%S)"
# the browser reference (Blink + Skia on the DirectWrite host): Edge, the Chromium on this VM
bash "$HERE/render-edge.sh" "$OUT" || echo "no browser reference" >&2
echo "sheets under $OUT"
