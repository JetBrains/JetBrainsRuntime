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
    skia-analytic)     echo "-Dsun.java2d.skia=true" ;;
    skia-marlin)       echo "-Dsun.java2d.skia=true -Dsun.java2d.skia.shapes=marlin" ;;
    skia-skia-msaa4)   echo "-Dsun.java2d.skia=true -Dsun.java2d.skia.shapes=skia -Dsun.java2d.skia.msaa=4" ;;
    skia-strokes-java) echo "-Dsun.java2d.skia=true -Dsun.java2d.skia.strokes=java" ;;
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
mkdir -p "$OUT/$MODE"
FIRST=${REP_START:-1}   # to continue a series in another batch
for rep in $(seq "$FIRST" $((FIRST + REPS - 1))); do
  for arm in $(printf '%s\n' "${ARMS[@]}" | shuf); do
    log="$OUT/$MODE/$arm-$rep.log"
    start=$(date +%s)
    # shellcheck disable=SC2046
    timeout 1800 "$JDK/bin/java.exe" $(props "$arm") -Dsun.java2d.uiScale=1 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
        -cp "$CLASSES" renderperf.RenderPerfTest -e="$MODE" ${TESTS:-} > "$log" 2>&1
    rc=$?
    echo "rep $rep $arm: exit=$rc $(( $(date +%s) - start )) s, $(grep -c ' FPS$' "$log") results"
  done
done
