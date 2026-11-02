#!/bin/bash
# The text sheets of every Java configuration on this Windows machine plus the platform's own rendering
# (dwref: DirectWrite through Direct2D, and GDI), and the comparison viewer over them:
#   run-win.sh [out-dir]   (default out/<today>)
# Layout as on the Mac: <out>/<arm>/<aa>/<positioning>/<scale>/<paper>.png with rows.txt at the root.
# Every Java arm states its LCD glyph source (sun.java2d.font.lcdGlyphs = gdi | scaler | directwrite).
# Arms: directwrite, gdi (native), software (Java software loops, GDI LCD glyphs), software-scaler (FreeType LCD glyphs), opengl-platform (WGL pipe, platform
# scaler; the VM has no Direct3D 9 and no Vulkan), skia-platform (Skia/Dawn pipe, platform scaler),
# software-skia-scaler, skia-scaler-{coverage,darkened,smoothed} (Skia pipe and scaler, the grayscale routes).
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
sheet software-skia-scaler software -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler
sheet skia-scaler-coverage volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler
sheet skia-scaler-darkened volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=darkened
sheet skia-scaler-smoothed volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=smoothed
sheet skia-scaler-platformcoverage volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platformCoverage
sheet skia-scaler-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platform
sheet software-skia-scaler-platformcoverage software -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platformCoverage
sheet software-skia-scaler-platform software -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platform
# the OpenGL pipe's gamma blend over the Skia scaler's coverage (the Mac's metal-skia-scaler analogue)
sheet opengl-skia-scaler volatile -Dsun.java2d.opengl=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler
# Skia's own text paths (hand-over 2026-10-26b): glyphs drawn by Skia from its strike (text=skia), shaped by
# Skia (text=shaper), laid out by Skia's paragraph engine (text=paragraph); with Skia's coverage, with
# text.smoothing, and with DirectWrite masks inside Skia's strike (gray=platform)
sheet skia-text volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=skia
sheet skia-text-smoothed volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=skia -Dsun.java2d.skia.text.smoothing=true
sheet skia-text-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=skia -Dsun.java2d.skia.text.gray=platform
sheet skia-shaper volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=shaper
sheet skia-shaper-smoothed volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=shaper -Dsun.java2d.skia.text.smoothing=true
sheet skia-shaper-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=shaper -Dsun.java2d.skia.text.gray=platform
sheet skia-paragraph-smoothed volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=paragraph -Dsun.java2d.skia.text.smoothing=true
sheet skia-paragraph-platform volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=paragraph -Dsun.java2d.skia.text.gray=platform
# the colour-keyed LCD strikes
sheet skia-scaler-platform-colour3 volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platform -Dsun.java2d.skia.text.lcdColorBits=3
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
colours skia-scaler-platform -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platform
colours skia-scaler-platformcoverage -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platformCoverage
colours skia-scaler-platform-colour3 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platform -Dsun.java2d.skia.text.lcdColorBits=3
colours skia-text-platform -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text=skia -Dsun.java2d.skia.text.gray=platform
colours software-skia-scaler-platform -Dsun.java2d.skia=false -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia -Dsun.java2d.font.lcdGlyphs=scaler -Dsun.java2d.skia.text.gray=platform
"$PY" "$KIT/viewer/make-viewer.py" "$OUT" --arms directwrite,gdi,chromium,software,software-scaler,software-dwlcd,software-dwlcd-gdiclassic,opengl-platform,opengl-skia-scaler,skia-platform,skia-platform-scaler,skia-platform-dwlcd,software-skia-scaler,skia-scaler-coverage,skia-scaler-hint-slight,skia-scaler-hint-normal,skia-scaler-darkened,skia-scaler-smoothed,skia-scaler-platformcoverage,skia-scaler-platform,skia-scaler-platform-colour3,software-skia-scaler-platformcoverage,software-skia-scaler-platform,skia-text,skia-text-smoothed,skia-text-platform,skia-shaper,skia-shaper-smoothed,skia-shaper-platform,skia-paragraph-smoothed,skia-paragraph-platform --set-dims aa,positioning,scale,paper,color --title "Text sheets $(basename "$OUT") (Windows): DirectWrite and GDI vs Java configurations"
