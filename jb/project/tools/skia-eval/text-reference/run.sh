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
sheet software software
sheet metal-platform volatile
sheet metal-platform-plain volatile -Dsun.java2d.metal.textGammaCorrection=false
sheet skia-platform volatile -Dsun.java2d.skia=true
sheet software-skia-scaler-coverage software -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet software-skia-scaler-cg software -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system
sheet metal-skia-scaler-coverage volatile -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet metal-skia-scaler-cg volatile -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system
sheet metal-skia-scaler-cg-plain volatile -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system -Dsun.java2d.metal.textGammaCorrection=false
sheet skia-scaler-coverage volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet skia-scaler-cg volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system
sheet skia-text volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet skia-text-darkening volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=system
sheet skia-text-cg volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia -Dsun.java2d.skia.text.blend=system
sheet skia-shaper volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.shaping=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none
sheet skia-shaper-darkening volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.shaping=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=system
sheet skia-shaper-cg volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.shaping=skia -Dsun.java2d.skia.text.blend=system
sheet skia-paragraph volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.paragraphs=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=system
sheet skia-paragraph-cg volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.paragraphs=skia -Dsun.java2d.skia.text.blend=system
bash "$KIT/viewer/chrome-ref/render.sh" "$OUT" || echo "Chrome reference skipped" >&2
bash "$HERE/run-colours.sh" "$OUT"   # the coloured sheets (gray only) into the same tree
python3 "$KIT/viewer/make-viewer.py" "$OUT" --arms coretext,coretext-continuous,coretext-unsmoothed,chrome,software,metal-platform,metal-platform-plain,skia-platform,software-skia-scaler-coverage,software-skia-scaler-cg,metal-skia-scaler-coverage,metal-skia-scaler-cg,metal-skia-scaler-cg-plain,skia-scaler-coverage,skia-scaler-cg,skia-scaler-cg-classes256,skia-scaler-cg-classes1,skia-text,skia-text-darkening,skia-text-cg,skia-shaper,skia-shaper-darkening,skia-shaper-cg,skia-paragraph,skia-paragraph-cg --set-dims aa,positioning,scale,paper,color --title "Text sheets $(basename "$OUT") (macOS): platform CoreText vs Java configurations"
