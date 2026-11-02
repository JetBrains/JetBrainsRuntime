#!/bin/bash
# The LCD items of the 2026-10-23 hand-over (section 5) on this machine:
#   5.1 does the Skia scaler's LCD text without fractional metrics come out grayscale (as on macOS)?
#   5.2 fringe statistics of the lcd sheets per arm (pixels with channel spread > 48, mean, max)
#   5.3 cross-process stray pixels: the lcd/fractional sheet rendered twice in fresh JVMs, diffed
# Usage: lcd-items.sh <out-dir>   (after run-win.sh produced the arms under <out-dir>)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-$HERE/../windows-aarch64-skia-main/images/jdk}"
BOOT=/d/Projects/JetBrainsRuntime/build/deps/boot-jdk27/jdk-27.0.1
OUT=$1
mkdir -p "$HERE/probes/classes"; "$BOOT/bin/javac" -d "$HERE/probes/classes" "$HERE/probes/FringeStats.java" "$HERE/probes/PixStats.java"
echo "=== 5.2 fringe statistics, lcd sheets, 1x and 2x, on white and on dark"
for arm in directwrite gdi software opengl-platform skia-platform software-skia-scaler skia-scaler-coverage; do
  for pos in whole-pixel fractional; do for scale in 1x 2x; do for paper in on-white on-dark; do
    f="$OUT/$arm/lcd/$pos/$scale/$paper.png"; [ -f "$f" ] && echo "$f"
  done; done; done
done | xargs "$BOOT/bin/java" -cp "$HERE/probes/classes" FringeStats | tee "$OUT/fringe-stats.txt"
echo; echo "=== 5.1 the Skia scaler's lcd sheets without fractional metrics: fringe pixels (0 would mean grayscale output)"
grep -E "skia-scaler-coverage/lcd/whole-pixel|software-skia-scaler/lcd/whole-pixel|skia-platform/lcd/whole-pixel|software/lcd/whole-pixel" "$OUT/fringe-stats.txt"
echo; echo "=== 5.3 cross-process determinism: lcd/fractional sheets rendered again in a fresh JVM, per arm"
mkdir -p "$HERE/classes"; "$JDK/bin/javac" -d "$HERE/classes" "$HERE/TextReference.java"
again() {   # again <arm> <software|volatile> [props]
  name=$1; how=$2; shift 2; flag=""; [ "$how" = volatile ] && flag="--volatile"
  rm -rf "$OUT/again-$name"
  "$JDK/bin/java" -Dsun.java2d.uiScale=1 "$@" -cp "$HERE/classes" TextReference "$OUT/again-$name" "$OUT/rows-again.txt" "$HERE/fonts" $flag > /dev/null 2>&1
  for pos in fractional whole-pixel; do for scale in 1x 2x; do for paper in on-white on-dark; do
    "$BOOT/bin/java" -cp "$HERE/probes/classes" PixStats "$OUT/$name/lcd/$pos/$scale/$paper.png" "$OUT/again-$name/lcd/$pos/$scale/$paper.png" "$name lcd/$pos/$scale/$paper"
  done; done; done
  rm -rf "$OUT/again-$name" "$OUT/rows-again.txt"
}
again software software -Dsun.java2d.d3d=false
again skia-platform volatile -Dsun.java2d.skia=true
again software-skia-scaler software -Dsun.java2d.d3d=false -Dsun.java2d.font.scaler=skia
again skia-scaler-coverage volatile -Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia
