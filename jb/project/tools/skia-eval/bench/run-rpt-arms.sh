#!/bin/bash
# RenderPerfTest over several arms, interleaved: run-rpt-arms.sh <jdk image> <out dir> <reps> <exec mode> [arm ...]
# Each repetition runs every arm once, in a fresh random order, with all tests (or $TESTS); REP_START
# numbers the first repetition (default 1), so a series can continue in a later batch.
# Logs: <out dir>/<exec mode>/<arm>-<rep>.log; summarise with rpt-summary.py.
set -u
R="${R:-$(cd "$(dirname "$0")/../../../../.." && pwd)}"
JDK=$(cd "$1" && pwd); OUT=$2; REPS=$3; MODE=$4; shift 4
ARMS=("$@"); [ ${#ARMS[@]} -gt 0 ] || ARMS=(d3d software skia-analytic skia-marlin skia-skia-msaa4 skia-strokes-java)

props() {
  case "$1" in
    d3d)               echo "-Dsun.java2d.d3d=true" ;;
    software)          echo "-Dsun.java2d.d3d=false" ;;
    raster)            echo "-Dsun.java2d.d3d=false -Dsun.java2d.skia.raster=true" ;;   # BufferedImages drawn by Skia's raster backend, GPU off
    raster-skiatext)   echo "-Dsun.java2d.d3d=false -Dsun.java2d.skia.raster=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text=skia" ;;
    vulkan)            echo "-Dsun.java2d.vulkan=Force" ;;   # JBR's Vulkan pipeline (experimental on Windows)
    skia-analytic)     echo "-Dsun.java2d.skia=true" ;;
    skia-marlin)       echo "-Dsun.java2d.skia=true -Dsun.java2d.skia.shapes=marlin" ;;
    skia-skia-msaa4)   echo "-Dsun.java2d.skia=true -Dsun.java2d.skia.shapes=skia -Dsun.java2d.skia.msaa=4" ;;
    skia-strokes-java) echo "-Dsun.java2d.skia=true -Dsun.java2d.skia.strokes=java" ;;
    skia-vulkan)       echo "-Dsun.java2d.skia=true -Dsun.java2d.skia.graphiteApi=vulkan" ;;   # Graphite on native Vulkan
    *) echo "unknown arm $1" >&2; exit 2 ;;
  esac
}

CLASSES="$OUT/classes"
if [ ! -f "$CLASSES/renderperf/RenderPerfTest.class" ]; then
  S="$R/test/jdk/performance/client/RenderPerfTest"
  mkdir -p "$CLASSES/renderperf/images"
  "$JDK/bin/javac.exe" -d "$CLASSES" "$S/src/renderperf/RenderPerfTest.java" || exit 2
  cp "$S"/resources/renderperf/images/* "$CLASSES/renderperf/images/"
fi

# The full list, unless $TESTS names tests. Direct3D 9 renders XOR so slowly that a single test runs
# for longer than ten minutes (FlatOval_XOR, 2026-10-22), so the arms in $XOR_SKIP_ARMS (default d3d)
# leave out the *_XOR tests.
ALL_TESTS=${TESTS:-$("$JDK/bin/java.exe" -cp "$CLASSES" renderperf.RenderPerfTest -h 2>&1 | tr -d '\r' \
    | sed -n 's/.*Supported test arguments <tests>://p')}
XOR_SKIP_ARMS=${XOR_SKIP_ARMS-d3d}
NO_XOR_TESTS=$(printf '%s\n' $ALL_TESTS | grep -v '_XOR$' | tr '\n' ' ')

mkdir -p "$OUT/$MODE"
FIRST=${REP_START:-1}
for rep in $(seq "$FIRST" $((FIRST + REPS - 1))); do
  for arm in $(printf '%s\n' "${ARMS[@]}" | shuf); do
    log="$OUT/$MODE/$arm-$rep.log"
    tests=$ALL_TESTS
    case " $XOR_SKIP_ARMS " in *" $arm "*) tests=$NO_XOR_TESTS ;; esac
    start=$(date +%s)
    # shellcheck disable=SC2046,SC2086
    timeout 900 "$JDK/bin/java.exe" $(props "$arm") -Dsun.java2d.uiScale=1 -Dsun.java2d.skia.pipelineCache=off -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -cp "$CLASSES" renderperf.RenderPerfTest -e="$MODE" $tests > "$log" 2>&1
    rc=$?
    echo "rep $rep $arm: exit=$rc $(( $(date +%s) - start )) s, $(grep -c ' FPS$' "$log") results"
  done
done
