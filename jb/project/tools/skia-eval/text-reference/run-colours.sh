#!/bin/bash
# Coloured text against AppKit: the macOS counterpart of the VM's ClearType-colour sheet (2026-10-26).
# macOS draws no colour sub-pixels, so the per-channel question does not arise; CoreGraphics' font
# smoothing depends on the text's luminance, so coloured text is the test of the cg route's luminance
# classes (16 by default; arms at 256 and at 1 class too). Each (colour, paper) is rendered through the
# normal sheet tools with the colours overridden (TEXTREF_INK / TEXTREF_PAPER); the gray sheets are then
# filed into the sheet tree as <arm>/gray/<positioning>/<scale>/color-on-<paper>/<colour>.png, so the
# viewer's Colors dimension gets "color on black" / "color on white" with the colour as a dropdown (the
# `color` set dimension, attached to `paper`). The 256- and 1-class arms also get black on white and white
# on black, which run.sh has for every other arm. Called by run.sh; usage: run-colours.sh OUT
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../env.sh"
WORK="$OUT_ROOT/text-reference"
FONTS="$JDK/lib/fonts"
OUT="${1:?the sheet tree, the OUT of run.sh}"
TMP="$OUT/.tmp"; mkdir -p "$OUT" "$TMP"
COLOURS="red:f75464 blue:56a8f5 orange:cf8e6d green:6aab73 purple:c77dba"
EXTREMES="black:000000 white:ffffff"   # only for the arms run.sh lacks
PAPERS="white:ffffff black:000000"
arm_java() {   # name, JVM options...
  name=$1; shift
  "$JDK/bin/java" -Dsun.java2d.uiScale=1 "$@" -cp "$WORK/classes" TextReference "$TMP/$name" "$TMP/rows-$name.txt" "$FONTS" --volatile 2>&1 | grep -v "^Picked up\|^WARNING\|^pipeline\|^wrote"
}
file() {   # arm, colour, paper: move the gray black-on-white sheets (the override wrote them) into place
  for pos in fractional whole-pixel; do for sc in 1x 2x; do
    src="$TMP/$1/gray/$pos/$sc/black-on-white.png"; dst="$OUT/$1/gray/$pos/$sc/color-on-$3/$2.png"
    [ -f "$src" ] && mkdir -p "$(dirname "$dst")" && mv "$src" "$dst"
  done; done
}
for cp in $COLOURS; do colour=${cp%%:*}; ink=${cp##*:}
  for pp in $PAPERS; do paper=${pp%%:*}; bg=${pp##*:}
    [ "$colour" = "$paper" ] && continue
    echo "== $colour on $paper $(date +%H:%M:%S)"
    export TEXTREF_INK=$ink TEXTREF_PAPER=$bg
    "$WORK/ctref" "$TMP/coretext" "$TMP/rows-ct.txt" "$FONTS" >/dev/null 2>&1; file coretext $colour $paper
    arm_java metal-platform; file metal-platform $colour $paper
    arm_java metal-skia-scaler-cg-plain -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system -Dsun.java2d.metal.textGammaCorrection=false; file metal-skia-scaler-cg-plain $colour $paper
    arm_java skia-scaler-cg -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system; file skia-scaler-cg $colour $paper
    arm_java skia-scaler-cg-classes256 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system -Dsun.java2d.skia.text.luminanceClasses=256; file skia-scaler-cg-classes256 $colour $paper
    arm_java skia-scaler-cg-classes1 -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system -Dsun.java2d.skia.text.luminanceClasses=1; file skia-scaler-cg-classes1 $colour $paper
    arm_java skia-scaler-coverage -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=corrected -Dsun.java2d.skia.text.darkening=none; file skia-scaler-coverage $colour $paper
    arm_java skia-text-cg -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia -Dsun.java2d.skia.text.blend=system; file skia-text-cg $colour $paper
    rm -rf "${TMP:?}"/*
  done
done
for cp in $EXTREMES; do colour=${cp%%:*}; ink=${cp##*:}
  for pp in $PAPERS; do paper=${pp%%:*}; bg=${pp##*:}
    [ "$colour" = "$paper" ] && continue
    echo "== $colour on $paper (luminance-class arms) $(date +%H:%M:%S)"
    export TEXTREF_INK=$ink TEXTREF_PAPER=$bg
    for classes in 256 1; do
      arm_java skia-scaler-cg-classes$classes -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.blend=system -Dsun.java2d.skia.text.luminanceClasses=$classes
      for pos in fractional whole-pixel; do for sc in 1x 2x; do
        src="$TMP/skia-scaler-cg-classes$classes/gray/$pos/$sc/black-on-white.png"; dst="$OUT/skia-scaler-cg-classes$classes/gray/$pos/$sc/$colour-on-$paper.png"
        [ -f "$src" ] && mkdir -p "$(dirname "$dst")" && mv "$src" "$dst"
      done; done
    done
    rm -rf "${TMP:?}"/*
  done
done
unset TEXTREF_INK TEXTREF_PAPER
rm -rf "${TMP:?}"
echo "colour sheets filed under $OUT"
