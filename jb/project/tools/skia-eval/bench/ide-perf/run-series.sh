#!/bin/bash
# IDE performance series (bench/ide-perf): run-series.sh <series dir> <first rep> <last rep> <arm> [<arm> ...]
# Every repetition runs each (arm, launch) once — launches typing and ui (LAUNCHES overrides) — in a fresh
# random order, into <series dir>/rep<N>-<arm>-<launch>; a later call with higher reps continues the
# series. After each launch: the launch's validity (the Skia arms' "Skia rendering enabled" line, the IDE
# reaching its end), and that nothing new appeared in ~/.m2. Summarise with summarize.py.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
SERIES=$1; FIRST=$2; LAST=$3; shift 3; ARMS=("$@")
LAUNCHES=(${LAUNCHES:-typing ui})
mkdir -p "$SERIES"
MARK="$SERIES/.m2-mark"; [ -f "$MARK" ] || touch "$MARK"
for rep in $(seq "$FIRST" "$LAST"); do
  jobs=()
  for arm in "${ARMS[@]}"; do for l in "${LAUNCHES[@]}"; do jobs+=("$arm|$l"); done; done
  order=$(printf '%s\n' "${jobs[@]}" | shuf | tr '\n' ' ')
  echo "rep $rep: $order"
  for job in $order; do
    arm=${job%|*}; launch=${job#*|}
    dir="$SERIES/rep$rep-$arm-$launch"; rm -rf "$dir"
    bash "$HERE/run-ide-perf.sh" "$arm" "$launch" "$dir" > "$SERIES/rep$rep-$arm-$launch.log" 2>&1
    end=$(grep -c "CommandLogger - %exitApp\|scene driver: exit requested" "$dir/idea.log" 2>/dev/null)
    skia=$(grep -o "Skia rendering enabled: [A-Z]*[^,]*" "$dir/stdout.txt" 2>/dev/null | head -1 | cut -c1-80)
    vk=$(grep -c "Vulkan rendering enabled: YES" "$dir/stdout.txt" 2>/dev/null)
    echo "  $arm $launch: $(tail -1 "$SERIES/rep$rep-$arm-$launch.log" | cut -c1-60) | end $end | ${skia:-no Skia line} | vulkan $vk | scenes $(wc -l < "$dir/scenes.tsv" 2>/dev/null || echo -) | m2 new $(find ~/.m2 -type f -newer "$MARK" 2>/dev/null | wc -l)"
  done
done
