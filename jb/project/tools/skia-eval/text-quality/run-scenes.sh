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
ARM=$1; THEME=$2; SCALE=$3; OUT="${4:-$HERE/out/$(date +%F)/scenes}"; SCENES="${5:-}"
case "$ARM" in A0) NAME=A0-software;; A1) NAME=A1-blend;; A2) NAME=A2-twopass;; A3) NAME=A3-accumulate;; A4) NAME=A4-cycle;; R1) NAME=R1-d3d9;; *) echo "unknown arm" >&2; exit 2;; esac
SEED="$IDE_STATE_ROOT/seed-$THEME"
rm -rf "$SEED"; mkdir -p "$SEED"
cp -r "$HERE/seed/common/." "$SEED/"; cp -r "$HERE/seed/$THEME/." "$SEED/"
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
rm -rf -- "${IDE_STATE_ROOT:?}/${NAME:?}/config" "${IDE_STATE_ROOT:?}/${NAME:?}/log" "${IDE_STATE_ROOT:?}/${NAME:?}/system"   # ${...:?}: an empty variable aborts instead of widening the removal
cp "$HERE/seed/project/workspace.xml" "$HERE/text-scene/.idea/workspace.xml"
cp "$HERE/seed/project/src/Hello.java" "$HERE/text-scene/src/Hello.java"
cp "$HERE/seed/project/README.md" "$HERE/text-scene/README.md"
DEST="$OUT/$NAME/$THEME-${SCALE}x"; rm -rf "$DEST"; mkdir -p "$DEST"   # a fresh destination: stale captures and markers of an earlier run must not survive
bash "$HERE/run-ide.sh" "$ARM" --scale "$SCALE" --seed "$SEED" -- "$HERE/text-scene" &
LAUNCHER=$!
# wait for the isolated IDE's frame: a java process of our JDK whose main window title names the project
IDE_PID=""
for i in $(seq 1 180); do
  sleep 1
  IDE_PID=$(powershell -NoProfile -Command "(Get-Process java -ErrorAction SilentlyContinue | Where-Object { \$_.Path -like '${JDK_WIN}\bin\*' -and \$_.MainWindowTitle -like 'text-scene*' } | Select-Object -First 1).Id" | tr -dc '0-9\n' | head -1)
  [ -n "$IDE_PID" ] && break
done
if [ -z "$IDE_PID" ]; then echo "the isolated IDE showed no frame within 180 s" >&2; kill $LAUNCHER 2>/dev/null; exit 1; fi
echo "isolated IDE pid $IDE_PID"
# indexing of the project and the SDK must be over before the scenes (dumb mode shows progress
# and red stripes, folds and hides code insight): wait for the exit from dumb mode, then settle
LOG="$IDE_STATE_ROOT/$NAME/log/idea.log"
# "exit dumb mode" is logged up to 90 s before the indexer's own "Finished for text-scene" line
# (2026-10-21 logs): wait for both, then settle
for i in $(seq 1 300); do
  sleep 1
  grep -q "exit dumb mode \[text-scene\]" "$LOG" 2>/dev/null && grep -q "Finished for text-scene" "$LOG" 2>/dev/null && break
done
grep -q "Finished for text-scene" "$LOG" 2>/dev/null || echo "warning: indexing not finished after 300 s" >&2
sleep 120   # the inspection pass, the run widget, the error stripe and the inspection widget arrive well after dumb mode ends, later still in the software arm: 15 s and 60 s settles left it without them (2026-10-21)
"$JDK/bin/java" -cp "$CLASSES" IdeScenes --out "$DEST" --pid "$IDE_PID" --fg "$(cygpath -w "$HERE/fg.ps1")" ${SCENES:+--scenes "$SCENES"} 2>&1 | tee "$DEST/scenes.log"
grep -E "Skia rendering|JRE:" "$IDE_STATE_ROOT/$NAME/stdout.txt" "$IDE_STATE_ROOT/$NAME/log/idea.log" 2>/dev/null | head -2 > "$DEST/arm.txt"
# a navigation popup that did not open in time lets the driver type into the editor: such a run is invalid
if ! cmp -s "$HERE/seed/project/src/Hello.java" "$HERE/text-scene/src/Hello.java" || ! cmp -s "$HERE/seed/project/README.md" "$HERE/text-scene/README.md"; then
  echo "INVALID RUN: the project sources were modified during the scenes" | tee "$DEST/INVALID.txt" >&2
  INVALID=1
elif grep -q "popup did not open: no key" "$DEST/scenes.log"; then
  echo "INVALID RUN: a popup did not open, the scenes were aborted" | tee "$DEST/INVALID.txt" >&2
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
