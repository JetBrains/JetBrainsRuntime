#!/bin/bash
# RasterShapesBench over arms and image types, interleaved: run-raster-bench.sh <jdk image> <out dir> <reps>
# Each repetition runs every (arm, image type) once, in a fresh random order. Arms: loops (the software
# loops), raster (-Dsun.java2d.skia.raster=true), raster-skiatext (raster + Skia's scaler and text route);
# image types 1 (INT_RGB, Swing's back buffer), 2 (INT_ARGB, the mirror path), 3 (INT_ARGB_PRE).
# All headless, the GPU off. <out dir>/results.tsv in run-text-benches.sh's format (bench = image type,
# configuration = workload); summarise with text-summary.py.
set -u
R="${R:-$(cd "$(dirname "$0")/../../../../.." && pwd)}"
JDK=$(cd "$1" && pwd); OUT=$2; REPS=$3
K="$R/jb/project/tools/skia-eval"
X="--add-exports java.desktop/sun.java2d=ALL-UNNAMED"
declare -A ARM=(
  [loops]=""
  [raster]="-Dsun.java2d.skia.raster=true"
  [raster-skiatext]="-Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia"
)
declare -A TYPE=([1]=INT_RGB [2]=INT_ARGB [3]=INT_ARGB_PRE)

mkdir -p "$OUT/classes" "$OUT/logs"
"$JDK/bin/javac.exe" $X -d "$OUT/classes" "$K/bench/RasterShapesBench.java" || exit 2
[ -f "$OUT/results.tsv" ] || printf 'rep\tbench\tconfiguration\tarm\tmetric\tms\n' > "$OUT/results.tsv"
for rep in $(seq 1 "$REPS"); do
  jobs=()
  for arm in "${!ARM[@]}"; do for t in 1 2 3; do jobs+=("$arm|$t"); done; done
  for job in $(printf '%s\n' "${jobs[@]}" | shuf); do
    arm=${job%|*}; t=${job#*|}
    log="$OUT/logs/$rep-$arm-$t.log"
    timeout 900 "$JDK/bin/java.exe" $X -Djava.awt.headless=true -Dsun.java2d.d3d=false -Dsun.java2d.uiScale=1 \
        ${ARM[$arm]} -cp "$OUT/classes" RasterShapesBench "$t" > "$log" 2>&1
    tr -d '\r' < "$log" | awk -F '\t' -v rep="$rep" -v b="${TYPE[$t]}" -v a="$arm" \
        'NF == 2 { printf "%s\t%s\t%s\t%s\tmedian\t%s\n", rep, b, $1, a, $2 }' >> "$OUT/results.tsv"
  done
  echo "rep $rep done: $(awk -v r="$rep" '$1 == r' "$OUT/results.tsv" | wc -l) values"
done
