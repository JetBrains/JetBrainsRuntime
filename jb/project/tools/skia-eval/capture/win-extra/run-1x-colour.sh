#!/bin/bash
# The 100 % scene batch of 2026-10-26 (go-ahead given on the screen), same image and arms as the
# 200 % batch of the afternoon: FreeType baseline, luminance-keyed platform route, 2-bit and 3-bit
# colour classes, light and dark. Stops after a run whose driver refused to type.
HERE="$(cd "$(dirname "$0")" && pwd)"
export JDK="$HERE/../windows-aarch64-skia-main/images/jdk"
OUT="$HERE/out/2026-10-26/scenes"
mkdir -p "$OUT"
for run in "A0 light" "T4 light" "T5 light" "T6 light" "A0 dark" "T4 dark" "T5 dark" "T6 dark"; do
  set -- $run
  echo "##### $1 $2 1x $(date +%T)"
  bash "$HERE/run-scenes.sh" "$1" "$2" 1 "$OUT" 2>&1 | tail -4
  if grep -q "foreground window is not the isolated IDE\|lost the isolated IDE" "$OUT"/*/"$2"-1x/scenes.log 2>/dev/null; then
    echo "##### driver refused to type, batch stopped $(date +%T)"; exit 3
  fi
done
echo "##### batch done $(date +%T)"
