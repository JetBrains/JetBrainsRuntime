#!/bin/bash
# RenderPerfTest on a Mac over several arms, interleaved (the Windows runner is run-rpt-arms.sh):
#   run-rpt-mac.sh <out dir> <reps> <exec mode> [arm ...]      arms: software raster raster-skiatext metal skia
# Each repetition runs every arm once in a fresh random order with $TESTS (default: all). Logs
# <out dir>/<exec mode>/<arm>-<rep>.log, summarised by rpt-summary.py. In -e=buffer the image is a
# BufferedImage: software = the loops, raster = Skia's raster backend on the same image. RPT_GC=<device-config>
# (RenderPerfTest -g) puts the frame on that display: on a Mac choose a 1x display, or the blit of the image to a
# Retina frame is part of every frame time (2026-11-02, the user's observation; -lg is not a listing flag here).
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
SRC="$REPO/test/jdk/performance/client/RenderPerfTest/src"
[ -f "$CLASSES/renderperf/RenderPerfTest.class" ] || "$JDK/bin/javac" -d "$CLASSES" "$SRC/renderperf/RenderPerfTest.java" || exit 2
cp -R "$SRC/../resources/." "$CLASSES/"   # the test's images (renderperf/images/duke.png) beside its classes, every time: a stale class dir once had none
TESTS=${TESTS:-}
for rep in $(seq "${REP_START:-1}" $(( ${REP_START:-1} + REPS - 1 ))); do
  for arm in $(printf '%s\n' "${ARMS[@]}" | awk 'BEGIN{srand()} {print rand() "\t" $0}' | sort -n | cut -f2); do
    log="$OUT/$MODE/$arm-$rep.log"
    echo "rep $rep arm $arm → $log"
    "$JDK/bin/java" -Dsun.java2d.uiScale=1 $(props "$arm") -cp "$CLASSES" renderperf.RenderPerfTest -e="$MODE" ${RPT_GC:+-g=$RPT_GC} $TESTS > "$log" 2>&1
  done
done
python3 "$HERE/rpt-summary.py" "$OUT/$MODE" software "${RPT_HZ:-60}"   # the display's refresh, for the vsync-cap check (buffer mode showed none at 60 Hz: hundreds of FPS)
