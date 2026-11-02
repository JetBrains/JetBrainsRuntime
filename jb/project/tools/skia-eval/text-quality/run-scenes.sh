#!/bin/bash
# IDE scenes for one arm, theme and scale (plan, instrument 3b):
#   run-scenes.sh <arm> <light|dark> <scale> [out-dir] [scenes]
# Starts the isolated IDE with the seed configuration (common + theme) on the scene project,
# waits for it, drives IdeScenes, captures under <out-dir>/<arm>/<theme>-<scale>x/, stops the IDE.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-$HERE/../windows-aarch64-skia/jdk}"
# IDE_STATE_ROOT: the arms' state (default ide/ here); SCENE_CLASSES: compile.sh's output;
# SDK_HOME: the JDK the seed's SDK table names (the seed was written on the VM)
export IDE_STATE_ROOT="${IDE_STATE_ROOT:-$HERE/ide}"
CLASSES="${SCENE_CLASSES:-$HERE/out-classes}"
JDK_WIN=$(cygpath -w "$(cd "$JDK" && pwd)")   # the isolated IDE is the java of this image, and nothing else
ARM=$1; THEME=$2; SCALE=$3;
OUT="${4:-$HERE/out/$(date +%F)/scenes}"; SCENES="${5:-}"
# "both": light and dark in one IDE session (the driver switches the theme); the indexes from a snapshot of a
# finished run (ide/snapshot/<IDE build>/system) — the first run without one indexes, exits the IDE cleanly and
# saves it (REFRESH_SNAPSHOT=1 to redo); DISPLAY_CHECKED=1 skips the display check (a round script checks once)
if [ "$THEME" = both ]; then SEED_THEME=light; THEMES="light,dark"; else SEED_THEME=$THEME; THEMES=$THEME; fi
case "$ARM" in A0) NAME=A0-software;; A1) NAME=A1-blend;; A2) NAME=A2-twopass;; A3) NAME=A3-accumulate;; A4) NAME=A4-cycle;; A5) NAME=A5-submit;; A6) NAME=A6-cycle-submit;; A7) NAME=A7-twopass-d3d12;; A8) NAME=A8-twopass-vulkan;; R0) NAME=R0-software-raster;; R5) NAME=R5-software-raster-skia-text;; R1) NAME=R1-d3d9;; R2) NAME=R2-vulkan;; *) echo "unknown arm" >&2; exit 2;; esac
SEED="$IDE_STATE_ROOT/seed-$SEED_THEME"
rm -rf "$SEED"; mkdir -p "$SEED"
cp -r "$HERE/seed/common/." "$SEED/"; cp -r "$HERE/seed/$SEED_THEME/." "$SEED/"
if [ -n "${SDK_HOME:-}" ]; then
  SDK_WIN=$(cygpath -w "$SDK_HOME"); SDK_FWD=$(cygpath -m "$SDK_HOME")
  sed -i "s|C:\\\\Users\\\\manuel.unterhofer\\\\AppData\\\\Local\\\\Programs\\\\IntelliJ IDEA 2\\\\jbr|${SDK_WIN//\\/\\\\}|g; s|C:/Users/manuel.unterhofer/AppData/Local/Programs/IntelliJ IDEA 2/jbr|$SDK_FWD|g" "$SEED/options/jdk.table.xml"
fi
# no isolated IDE may be running from an earlier run: its frame would be found instead of ours
stop_ours() { powershell -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Where-Object { \$_.Path -like '${JDK_WIN}\\bin\\*' } | Stop-Process -Force" > /dev/null 2>&1; }
stop_ours; sleep 2
# a fresh config and log per run so the seed is what the IDE sees and the wait below reads this run;
# the project's workspace file too: the IDE saves editor scroll, caret and tool-window state into it
# the system directory too (indexes, caches): a stale one left one arm's IDE in another highlighting
# state than the others (a modifier painted in the "redundant" gray, an extra inline hint), which
# looked like a compositing defect of that arm for a day. Re-indexing costs about a minute per run.
rm -rf -- "${IDE_STATE_ROOT:?}/${NAME:?}/config" "${IDE_STATE_ROOT:?}/${NAME:?}/log" "${IDE_STATE_ROOT:?}/${NAME:?}/system" "${IDE_STATE_ROOT:?}/${NAME:?}/plugins"
# the scene-driver plugin (scene-driver/build-plugin.sh with IDE_HOME=<install dir> builds it against the IDE) is the driver's way into the IDE
[ -f "$HERE/seed/common/plugins/scene-driver/lib/scene-driver.jar" ] || { echo "no scene-driver plugin: run scene-driver/build-plugin.sh" >&2; exit 1; }
mkdir -p "$IDE_STATE_ROOT/$NAME/plugins"; cp -R "$HERE/seed/common/plugins/." "$IDE_STATE_ROOT/$NAME/plugins/"
# the indexes: a copy of the snapshot when there is one for this IDE build
BUILD=$(grep -o '"buildNumber": *"[^"]*"' "${IDE:-$(cygpath -u "$LOCALAPPDATA")/Programs/IntelliJ IDEA}/product-info.json" 2>/dev/null | sed 's/.*: *"//; s/"$//')
SNAP="$IDE_STATE_ROOT/snapshot/${BUILD:-unknown}/system"
SNAPSHOT_USED=0
if [ -d "$SNAP" ] && [ -z "${REFRESH_SNAPSHOT:-}" ]; then cp -R "$SNAP" "$IDE_STATE_ROOT/$NAME/system" && SNAPSHOT_USED=1; fi   # ${...:?}: an empty variable aborts instead of widening the removal
cp "$HERE/seed/project/workspace.xml" "$HERE/text-scene/.idea/workspace.xml"
cp "$HERE/seed/project/src/Hello.java" "$HERE/text-scene/src/Hello.java"
cp "$HERE/seed/project/README.md" "$HERE/text-scene/README.md"
ARMDIR="$OUT/$NAME"; DEST="$ARMDIR/$SEED_THEME-${SCALE}x"; rm -rf "$DEST"; mkdir -p "$DEST"; [ "$THEME" = both ] && { rm -rf "$ARMDIR/dark-${SCALE}x"; mkdir -p "$ARMDIR/dark-${SCALE}x"; }   # a fresh destination: stale captures and markers of an earlier run must not survive
# the display must run at the requested scale with room for the 1728x980 frame (the user sets it in Windows'
# display settings; this waits and tells them what to set); the driver places and captures the frame at its own bounds
WINDOW="${WINDOW:-1728x980}"
[ "${DISPLAY_CHECKED:-}" = 1 ] || "$JDK/bin/java" -cp "$CLASSES" ScreenBounds --require "$SCALE" "${WINDOW%x*}" "${WINDOW#*x}" || exit 1
bash "$HERE/run-ide.sh" "$ARM" --scale "$SCALE" --seed "$SEED" -- "$HERE/text-scene" &
LAUNCHER=$!
# wait for the isolated IDE's frame: a java process of our JDK whose main window title names the project
IDE_PID=""
for i in $(seq 1 180); do
  sleep 1
  if ! kill -0 $LAUNCHER 2>/dev/null; then echo "the launcher exited before the IDE showed a frame (see above)" >&2; exit 1; fi
  IDE_PID=$(powershell -NoProfile -Command "(Get-Process java -ErrorAction SilentlyContinue | Where-Object { \$_.Path -like '${JDK_WIN}\bin\*' -and \$_.MainWindowTitle -like 'text-scene*' } | Select-Object -First 1).Id" | tr -dc '0-9\n' | head -1)
  [ -n "$IDE_PID" ] && break
done
if [ -z "$IDE_PID" ]; then echo "the isolated IDE showed no frame within 180 s" >&2; kill $LAUNCHER 2>/dev/null; exit 1; fi
echo "isolated IDE pid $IDE_PID"
# indexing of the project and the SDK must be over before the scenes (dumb mode shows progress
# and red stripes, folds and hides code insight): wait for the exit from dumb mode, then settle
LOG="$IDE_STATE_ROOT/$NAME/log/idea.log"
# indexing over: as many exits from dumb mode as entries (the SDK pre-indexing exits once early, the
# project's indexing enters again), for a second without a new entry
SMART=0
for i in $(seq 1 1500); do
  sleep 0.2
  ENTERS=$(grep -c "enter dumb mode \[text-scene\]" "$LOG" 2>/dev/null); EXITS=$(grep -c "exit dumb mode \[text-scene\]" "$LOG" 2>/dev/null); ENTERS=${ENTERS:-0}; EXITS=${EXITS:-0}
  if [ "$EXITS" -ge 1 ] && [ "$EXITS" -ge "$ENTERS" ]; then SMART=$((SMART + 1)); else SMART=0; fi
  [ "$SMART" -ge 3 ] && break
done
[ "$SMART" -ge 3 ] || echo "warning: still in dumb mode after 300 s" >&2
PORT=$(grep -o "built-in server started, port [0-9]*" "$LOG" | head -1 | grep -o "[0-9]*$")
[ -n "$PORT" ] || { echo "the IDE's built-in server port is not in its log" >&2; taskkill //PID "$IDE_PID" //T //F > /dev/null 2>&1; exit 1; }
# the inspection pass, the run widget, the error stripe and the inspection widget arrive well after dumb
# mode ends, later still in the software arm: the driver waits until the screen has been unchanged for
# 4 s, at most 150 s (--quiesce; 2026-11-02: a fixed 120 s before, which 15 s and 60 s had under-run)
"$JDK/bin/java" -cp "$CLASSES" IdeScenes --out "$ARMDIR" --pid "$IDE_PID" --fg "$(cygpath -w "$HERE/fg.ps1")" --port "$PORT" --quiesce 4000 --window "$WINDOW" --scale "$SCALE" --themes "$THEMES" --exit ${SCENES:+--scenes "$SCENES"} 2>&1 | tee "$ARMDIR/scenes.log"
DRIVER_STATUS=${PIPESTATUS[0]}
grep -E "Skia rendering|JRE:" "$IDE_STATE_ROOT/$NAME/stdout.txt" "$IDE_STATE_ROOT/$NAME/log/idea.log" 2>/dev/null | head -2 > "$DEST/arm.txt"
[ "$THEME" = both ] && cp "$DEST/arm.txt" "$ARMDIR/dark-${SCALE}x/arm.txt"
# without a snapshot the driver asked the IDE to exit cleanly: wait for it, then keep its system directory
if [ "$SNAPSHOT_USED" = 0 ] && [ "$DRIVER_STATUS" = 0 ]; then
  for i in $(seq 1 60); do tasklist //FI "PID eq $IDE_PID" 2>/dev/null | grep -q "$IDE_PID" || break; sleep 0.25; done
  if tasklist //FI "PID eq $IDE_PID" 2>/dev/null | grep -q "$IDE_PID"; then echo "the IDE did not exit on request in 15 s: a close request to its window" >&2; taskkill //PID "$IDE_PID" > /dev/null 2>&1; for i in $(seq 1 120); do tasklist //FI "PID eq $IDE_PID" 2>/dev/null | grep -q "$IDE_PID" || break; sleep 0.25; done; fi
  if ! tasklist //FI "PID eq $IDE_PID" 2>/dev/null | grep -q "$IDE_PID"; then rm -rf "$SNAP"; mkdir -p "$(dirname "$SNAP")"; cp -R "$IDE_STATE_ROOT/$NAME/system" "$SNAP" && echo "index snapshot saved: $SNAP"; else echo "warning: the IDE did not exit in 30 s, no snapshot" >&2; fi
fi
# a navigation popup that did not open in time lets the driver type into the editor: such a run is invalid
if ! cmp -s "$HERE/seed/project/src/Hello.java" "$HERE/text-scene/src/Hello.java" || ! cmp -s "$HERE/seed/project/README.md" "$HERE/text-scene/README.md"; then
  echo "INVALID RUN: the project sources were modified during the scenes" | tee "$ARMDIR/INVALID.txt" >&2
  INVALID=1
elif grep -q "popup did not open: no key" "$DEST/scenes.log"; then
  echo "INVALID RUN: a popup did not open, the scenes were aborted" | tee "$ARMDIR/INVALID.txt" >&2
  INVALID=1
fi
# JFR_DUMP=<recording name>: dump that recording (started through IDE_EXTRA_VMOPTIONS) into the
# destination before the stop, which would lose it otherwise (the stop is a forced kill)
if [ -n "${JFR_DUMP:-}" ]; then
  "$JDK/bin/jcmd" "$IDE_PID" JFR.dump name="$JFR_DUMP" filename="$(cygpath -w "$DEST")\\recording.jfr" > "$DEST/jfr-dump.txt" 2>&1
fi
# stop the isolated IDE, and anything of ours that survived
taskkill //PID "$IDE_PID" //T //F > /dev/null 2>&1
stop_ours
kill $LAUNCHER 2>/dev/null
echo "done: $DEST"
# one retry for an invalid run (RETRY=0 in the environment disables it)
if [ "${INVALID:-0}" = 1 ] && [ "${RETRY:-1}" = 1 ]; then
  echo "retrying $NAME $THEME $SCALE once"
  rm -rf "$DEST.invalid"; mv "$DEST" "$DEST.invalid"   # keep the failed attempt's log and miss screenshots
  RETRY=0 exec bash "$0" "$ARM" "$THEME" "$SCALE" "$OUT" ${SCENES:+"$SCENES"}
fi
