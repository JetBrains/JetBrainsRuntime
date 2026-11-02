#!/bin/bash
# RenderPerfTest on a Mac over several arms, interleaved (the Windows runner is run-rpt-arms.sh):
#   run-rpt-mac.sh <out dir> <reps> <exec mode> [arm ...]      arms: software raster raster-skiatext metal skia
# Each repetition runs every arm once in a fresh random order with $TESTS (default: all). Logs
# <out dir>/<exec mode>/<arm>-<rep>.log, summarised by rpt-summary.py. In -e=buffer the image is a
# BufferedImage: software = the loops, raster = Skia's raster backend on the same image.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../env.sh"
OUT=$1; REPS=$2; MODE=$3; shift 3
ARMS=("$@"); [ ${#ARMS[@]} -gt 0 ] || ARMS=(software raster)
props() {
  case "$1" in
    software)        echo "" ;;
    raster)          echo "-Dsun.java2d.skia.raster=true" ;;
    raster-skiatext) echo "-Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.strike=skia" ;;
    metal)           echo "-Dsun.java2d.metal=true" ;;
    skia)            echo "-Dsun.java2d.skia=true" ;;
    *) echo "unknown arm $1" >&2; exit 2 ;;
  esac
}
CLASSES="$OUT_ROOT/rpt/classes"; mkdir -p "$CLASSES" "$OUT/$MODE"
[ -f "$CLASSES/renderperf/RenderPerfTest.class" ] || "$JDK/bin/javac" -d "$CLASSES" "$REPO/test/jdk/performance/client/RenderPerfTest/src/renderperf/RenderPerfTest.java" || exit 2
TESTS=${TESTS:-}
for rep in $(seq "${REP_START:-1}" $(( ${REP_START:-1} + REPS - 1 ))); do
  for arm in $(printf '%s\n' "${ARMS[@]}" | awk 'BEGIN{srand()} {print rand() "\t" $0}' | sort -n | cut -f2); do
    log="$OUT/$MODE/$arm-$rep.log"
    echo "rep $rep arm $arm → $log"
    "$JDK/bin/java" -Dsun.java2d.uiScale=1 $(props "$arm") -cp "$CLASSES" renderperf.RenderPerfTest -e="$MODE" $TESTS > "$log" 2>&1
  done
done
python3 "$HERE/rpt-summary.py" "$OUT/$MODE" software
