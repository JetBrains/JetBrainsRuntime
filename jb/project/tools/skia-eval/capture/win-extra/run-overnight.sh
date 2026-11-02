#!/bin/bash
# The overnight scene batch of 2026-10-26/27 (user's go-ahead, VM unattended): the nine arms of
# hand-over 2026-10-26b at 200 %, then 100 %, 150 %, 125 %, light and dark, on the shared-tip image
# with Skia's ICU data. After every (scale, theme) group its SceneDiff tables (against A0 and
# against T4) are built and the group is copied to the Mac share, so the morning finds whatever is
# done. Stops the whole batch after a run whose driver refused to type (an unattended retry could
# type into whatever owns the screen).
HERE="$(cd "$(dirname "$0")" && pwd)"
export JDK="$HERE/../windows-aarch64-skia-main/images/jdk"
OUT="$HERE/out/2026-10-27/scenes"
LOG="$HERE/out/2026-10-27-overnight.log"
SHARE='\\Mac\Projects\JetBrainsRuntime\build\text-reference-win\scenes-2026-10-27'
mkdir -p "$OUT"
ARMS="A0 T4 T0 O0 S5 F5 T1 T5 T6"
J="$JDK/bin/java"
share_copy() {   # copies the whole scene tree and the log to the Mac share (incremental: Copy-Item overwrites)
  powershell -NoProfile -Command "New-Item -ItemType Directory -Force '$SHARE' | Out-Null; Copy-Item 'D:\\Projects\\JetBrainsRuntime\\build\\text-quality\\out\\2026-10-27\\scenes\\*' '$SHARE' -Recurse -Force; Copy-Item '$(cygpath -w "$LOG")' '$SHARE' -Force" 2>&1 | tail -1
}
tables() {   # theme, scale
  "$J" -cp "$OUT_ROOT/capture/classes" SceneDiff --out "$OUT" --theme "$1" --scale "$2" --base A0-software \
       --arms T4-skia-scaler-platform T0-skia-platform O0-opengl S5-software-skia-scaler-platform F5-skia-full-platform T1-skia-scaler-coverage T5-skia-scaler-platform-colour2 T6-skia-scaler-platform-colour3 2>&1 | tail -1
  mv "$OUT/scenes-$1-$2x.md" "$OUT/scenes-$1-$2x-vs-A0.md" 2>/dev/null; rm -rf "$OUT/review-$1-$2x-vs-A0"; mv "$OUT/review-$1-$2x" "$OUT/review-$1-$2x-vs-A0" 2>/dev/null
  "$J" -cp "$OUT_ROOT/capture/classes" SceneDiff --out "$OUT" --theme "$1" --scale "$2" --base T4-skia-scaler-platform \
       --arms T5-skia-scaler-platform-colour2 T6-skia-scaler-platform-colour3 F5-skia-full-platform S5-software-skia-scaler-platform 2>&1 | tail -1
  mv "$OUT/scenes-$1-$2x.md" "$OUT/scenes-$1-$2x-vs-T4.md" 2>/dev/null; rm -rf "$OUT/review-$1-$2x-vs-T4"; mv "$OUT/review-$1-$2x" "$OUT/review-$1-$2x-vs-T4" 2>/dev/null
}
echo "##### overnight batch start $(date +%F' '%T)" | tee -a "$LOG"
for scale in 2 1 1.5 1.25; do
  for theme in light dark; do
    for arm in $ARMS; do
      echo "##### $arm $theme ${scale}x $(date +%T)" | tee -a "$LOG"
      RETRY=0 bash "$HERE/run-scenes.sh" "$arm" "$theme" "$scale" "$OUT" 2>&1 | tail -3 | tee -a "$LOG"
      if grep -q "foreground window is not the isolated IDE\|foreground application is not the isolated IDE\|lost the isolated IDE\|is gone before" "$OUT"/*/"$theme-${scale}x"/scenes.log 2>/dev/null; then
        echo "##### driver refused to type, batch stopped $(date +%T)" | tee -a "$LOG"
        share_copy
        exit 3
      fi
    done
    echo "##### tables and copy for $theme ${scale}x $(date +%T)" | tee -a "$LOG"
    tables "$theme" "$scale" | tee -a "$LOG"
    share_copy | tee -a "$LOG"
  done
done
echo "##### overnight batch done $(date +%F' '%T)" | tee -a "$LOG"
share_copy | tee -a "$LOG"
