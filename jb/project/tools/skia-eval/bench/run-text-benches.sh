#!/bin/bash
# Text benches (HANDOVER.md, Phase 2.2), interleaved: run-text-benches.sh <jdk image> <out dir> <reps>
# Per repetition every (bench, configuration, arm) runs once as its own JVM, in a fresh random order:
#   LcdTextBench <lines> 60 lcd cheap [grid]  for 100 / 500 / 1000 lines and the 90-line grid
#   GlyphChurnBench <distinct> [lcd] [software]  for 200 / 600 / 1500 / 3000 glyphs, gray and LCD
#   StartupCost  (first off-screen frame and first window paint)
# Results: <out dir>/results.tsv (rep, bench, configuration, arm, metric, ms); logs in <out dir>/logs.
# JDK_B=<second image>: the Skia and software arms also run on it, as b-<arm>, in the same shuffled
# repetitions (an A/B of two builds). The pipeline cache is off in every arm (no files in the home
# directory, and StartupCost measures the compiles).
set -u
R="${R:-$(cd "$(dirname "$0")/../../../../.." && pwd)}"
JDK=$(cd "$1" && pwd); OUT=$(mkdir -p "$2" && cd "$2" && pwd); REPS=$3
K="$R/jb/project/tools/skia-eval"
X="--add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED"
XR="-Dsun.java2d.skia.pipelineCache=off"   # java only (javac takes $X too)
JDK_B=${JDK_B:+$(cd "$JDK_B" && pwd)}

declare -A ARM=(
  [skia]="-Dsun.java2d.skia=true"
  [skia-lcd-twopass]="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass"
  [skia-lcd-blend]="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend"
  [software]="-Dsun.java2d.d3d=false"
  [d3d]="-Dsun.java2d.d3d=true"
)

mkdir -p "$OUT/classes" "$OUT/logs"
"$JDK/bin/javac.exe" $X -d "$OUT/classes" "$K/smoke/LcdTextBench.java" "$K/smoke/StartupCost.java" "$K/smoke/SkiaRender.java" "$K/bench/GlyphChurnBench.java" || exit 2

jobs=()
for arm in skia skia-lcd-twopass skia-lcd-blend software d3d; do
  for n in 100 500 1000; do jobs+=("LcdTextBench|$n lcd|$arm|LcdTextBench $n 60 lcd cheap"); done
  jobs+=("LcdTextBench|90 lcd grid|$arm|LcdTextBench 90 60 lcd cheap grid")
done
for d in 200 600 1500 3000; do
  for aa in gray lcd; do
    a=""; [ "$aa" = lcd ] && a=lcd
    jobs+=("GlyphChurnBench|$d $aa|skia|GlyphChurnBench $d $a")
    jobs+=("GlyphChurnBench|$d $aa|software|GlyphChurnBench $d $a software")
  done
done
for arm in skia software d3d; do jobs+=("StartupCost|-|$arm|StartupCost"); done
if [ -n "$JDK_B" ]; then
  for arm in skia skia-lcd-twopass skia-lcd-blend software; do
    for n in 100 500 1000; do jobs+=("LcdTextBench|$n lcd|b-$arm|LcdTextBench $n 60 lcd cheap"); done
    jobs+=("LcdTextBench|90 lcd grid|b-$arm|LcdTextBench 90 60 lcd cheap grid")
  done
  for d in 200 600 1500 3000; do
    jobs+=("GlyphChurnBench|$d gray|b-skia|GlyphChurnBench $d")
    jobs+=("GlyphChurnBench|$d lcd|b-skia|GlyphChurnBench $d lcd")
  done
  jobs+=("StartupCost|-|b-skia|StartupCost")
fi

[ -f "$OUT/results.tsv" ] || printf 'rep\tbench\tconfiguration\tarm\tmetric\tms\n' > "$OUT/results.tsv"
FIRST=${REP_START:-1}
for rep in $(seq "$FIRST" $((FIRST + REPS - 1))); do
  printf '%s\n' "${jobs[@]}" | shuf | while IFS='|' read -r bench conf arm cmd; do
    log="$OUT/logs/$rep-$bench-${conf// /_}-$arm.log"
    # shellcheck disable=SC2086
    jdk=$JDK; a=$arm; [ "${arm#b-}" != "$arm" ] && { jdk=$JDK_B; a=${arm#b-}; }
    timeout 600 "$jdk/bin/java.exe" $X $XR ${ARM[$a]} -Dsun.java2d.uiScale=1 -cp "$OUT/classes" $cmd > "$log" 2>&1
    case "$bench" in
      LcdTextBench)    v=$(grep -oE 'median=[0-9.]+' "$log" | cut -d= -f2); metric=median ;;
      GlyphChurnBench) v=$(grep -oE 'median [0-9.]+' "$log" | cut -d' ' -f2); metric=median ;;
      StartupCost)
        f1=$(grep -m1 -oE 'off-screen frame 1: [0-9.]+' "$log" | grep -oE '[0-9.]+$')
        f5=$(grep -m1 -oE 'off-screen frame 5: [0-9.]+' "$log" | grep -oE '[0-9.]+$')
        w=$(grep -m1 -oE 'window first paint\+sync: [0-9.]+' "$log" | grep -oE '[0-9.]+$')
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$rep" "$bench" "$conf" "$arm" frame1 "${f1:-NA}" "$rep" "$bench" "$conf" "$arm" frame5 "${f5:-NA}" >> "$OUT/results.tsv"
        metric=window; v=$w ;;
    esac
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$rep" "$bench" "$conf" "$arm" "$metric" "${v:-NA}" >> "$OUT/results.tsv"
  done
  echo "rep $rep done: $(awk -F'\t' -v r="$rep" '$1==r' "$OUT/results.tsv" | wc -l) values, $(awk -F'\t' -v r="$rep" '$1==r && $6=="NA"' "$OUT/results.tsv" | wc -l) NA"
done
