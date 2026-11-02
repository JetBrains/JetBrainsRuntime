#!/bin/bash
# The text sheets of every Java configuration on this Windows machine plus the platform's own rendering
# (dwref: DirectWrite through Direct2D, and GDI), and the comparison viewer over them:
#   run-win.sh [out-dir]   (default out/<today>)
# Layout as on the Mac: <out>/<arm>/<aa>/<positioning>/<scale>/<paper>.png with rows.txt at the root.
# Every Java arm states its LCD glyph source (sun.java2d.font.lcdGlyphs = gdi | scaler | directwrite).
# Arms: directwrite, gdi (native), software (Java software loops, GDI LCD glyphs), software-scaler (FreeType LCD glyphs), opengl-platform (WGL pipe, platform
# scaler; the VM has no Direct3D 9 and no Vulkan), skia-platform (Skia/Dawn pipe, platform scaler),
# software-skia-scaler, skia-scaler-coverage (Skia pipe and scaler, Skia's own coverage), skia-scaler-platform /
# -platformcoverage (the platform's masks: the default blend, and plain coverage), the text-path arms (skia-text,
# skia-shaper, skia-paragraph: Skia's strike / shaping / paragraphs, with Skia's coverage or the platform's blend).
# Six-dimension spellings since 2026-11-02 (d3363ef442c9); the smoothed and darkened emulations are gone.
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
# sheet <name> <software|volatile> [JVM options...]
sheet() {
  name=$1; how=$2; shift 2
  flag=""; [ "$how" = volatile ] && flag="--volatile"
  "$JDK/bin/java" -Dsun.java2d.uiScale=1 "$@" -cp "$HERE/classes" TextReference "$OUT/$name" "$OUT/rows-$name.txt" "$FONTS" $flag 2>&1 | grep -v "^Picked up\|^WARNING\|^pipeline" || true
  diff -q --strip-trailing-cr "$OUT/rows-$name.txt" "$OUT/rows.txt" > /dev/null || echo "row order differs: $name" >&2
  rm -f "$OUT/rows-$name.txt"
}
sheet software software -Dsun.java2d.d3d=false -Dsun.java2d.font.lcdGlyphs=gdi
sheet software-scaler software -Dsun.java2d.d3d=false -Dsun.java2d.font.lcdGlyphs=scaler
sheet opengl-platform volatile -Dsun.java2d.opengl=true -Dsun.java2d.font.lcdGlyphs=gdi
sheet skia-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.lcdGlyphs=gdi
sheet skia-platform-scaler volatile -Dsun.java2d.skia=true -Dsun.java2d.font.lcdGlyphs=scaler
sheet software-skia-scaler software -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet skia-scaler-coverage volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet skia-scaler-platformcoverage volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.blend=plain -Dsun.java2d.skia.text.darkening=none
sheet skia-scaler-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler
sheet software-skia-scaler-platformcoverage software -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.blend=plain -Dsun.java2d.skia.text.darkening=none
sheet software-skia-scaler-platform software -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler
# the OpenGL pipe's gamma blend over the Skia scaler's coverage (the Mac's metal-skia-scaler analogue)
sheet opengl-skia-scaler volatile -Dsun.java2d.opengl=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
# Skia's own text paths (hand-over 2026-10-26b): glyphs drawn by Skia from its strike (text=skia), shaped by
# Skia (text=shaper), laid out by Skia's paragraph engine (text=paragraph); with Skia's coverage, with
# text.smoothing, and with DirectWrite masks inside Skia's strike (gray=platform)
sheet skia-text volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.strike=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet skia-text-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.strike=skia
sheet skia-shaper volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.shaping=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet skia-shaper-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.shaping=skia
sheet skia-paragraph-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.paragraphs=skia
# the colour-keyed LCD strikes
sheet skia-scaler-platform-colour3 volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.lcdColorBits=3
# the browser reference (Blink + Skia on the DirectWrite host): Edge, the Chromium on this VM
bash "$HERE/render-edge.sh" "$OUT" || echo "no browser reference" >&2
# the ClearType rows in the IDE's keyword colours, filed under lcd/fractional/<scale>/color-on-<paper>/<colour>.png
# of the arms where ClearType's per-channel correction is the question (hand-over 2026-10-26c)
"$HERE/dwref.exe" "$(cygpath -w "$OUT")" "$(cygpath -w "$OUT/rows-colours.txt")" "$(cygpath -w "$FONTS")" --colours > /dev/null; rm -f "$OUT/rows-colours.txt"
colours() {   # name, JVM options
  name=$1; shift
  "$JDK/bin/java" -Dsun.java2d.uiScale=1 "$@" -cp "$HERE/classes" TextReference "$OUT/$name" "$OUT/rows-$name.txt" "$FONTS" --volatile --colours 2>&1 | grep -v "^Picked up\|^WARNING\|^pipeline\|^wrote" || true
  rm -f "$OUT/rows-$name.txt"
}
colours skia-scaler-platform -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler
colours skia-scaler-platformcoverage -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.blend=plain -Dsun.java2d.skia.text.darkening=none
colours skia-scaler-platform-colour3 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.lcdColorBits=3
colours skia-text-platform -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.strike=skia
colours software-skia-scaler-platform -Dsun.java2d.skia=false -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler
# no viewer here (2026-11-02): machines deliver the tree, the Mac builds the viewers and the catalog
