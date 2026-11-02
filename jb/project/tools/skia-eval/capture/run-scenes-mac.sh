#!/bin/bash
# IDE scenes for one arm, theme and scale (the VM's instrument 3b, on macOS):
#   run-scenes.sh <arm> <light|dark|both> <scale> [out-dir] [scenes]
# "both" captures light and dark in one IDE session (the driver switches the theme). The project's indexes
# come from a snapshot of a finished run (ide/snapshot/<IDE build>/system, an APFS clone: no indexing per
# run); the first run without one indexes, exits the IDE cleanly and saves it (REFRESH_SNAPSHOT=1 to redo).
# DISPLAY_CHECKED=1 skips the display check (a round script checks once).
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
# no display sleep, screen saver or lock while this runs (the window list goes empty when the screen locks: a run of 2026-11-02 found no frame)
caffeinate -dimsu -w $$ &
source "$HERE/../env.sh"
WORK="$OUT_ROOT/capture"   # everything this routine generates: IDE state, classes, the helper, the plugin, snapshots, captures
ARM=$1; THEME=$2; SCALE=$3; OUT="${4:-$WORK/out/$(date +%F)/scenes}"; SCENES="${5:-}"
if [ "$THEME" = both ]; then SEED_THEME=light; THEMES="light,dark"; else SEED_THEME=$THEME; THEMES=$THEME; fi
case "$ARM" in M0) NAME=M0-metal;; M1) NAME=M1-default;; T0m) NAME=T0m-skia-metalprop;; T0) NAME=T0-skia-platform;; T1) NAME=T1-skia-scaler;; T2) NAME=T2-skia-darkened;; T3) NAME=T3-skia-smoothed;; T5) NAME=T5-skia-cg;; M5) NAME=M5-metal-cg-plain;; F5) NAME=F5-skia-full-cg;; *) echo "unknown arm" >&2; exit 2;; esac
SEED="$WORK/ide/seed-$SEED_THEME"
rm -rf "$SEED"; mkdir -p "$SEED"
cp -R "$HERE/seed/common/." "$SEED/"; cp -R "$HERE/seed/mac/." "$SEED/"; cp -R "$HERE/seed/$SEED_THEME/." "$SEED/"
# the seed's SDK entry (jdk.table.xml, "jbr-25") must point at the IDE's own JBR, wherever the Toolbox put the app
# this week (the EAP became "Release Candidate" on 2026-11-02 and every editor showed a "JDK not found" banner)
if [ -z "${APP:-}" ]; then APP=$(ls -td "$HOME/Applications/IntelliJ IDEA 2026.3"*.app 2>/dev/null | head -1); fi
export APP="${APP:-$HOME/Applications/IntelliJ IDEA.app}"
# every reference — the home path and the 50-odd jrt:// class and source roots — or the classes stay unresolved
# and the editor shows red symbols and a "Project JDK is misconfigured" banner (seen 2026-11-02)
OLD_HOME=$(grep -o '<homePath value="[^"]*"' "$SEED/options/jdk.table.xml" | head -1 | sed 's/<homePath value="//; s/"$//')
NEW_HOME="$APP/Contents/jbr/Contents/Home"
[ -d "$NEW_HOME" ] || echo "warning: $NEW_HOME does not exist" >&2
sed -i '' "s|$OLD_HOME|$NEW_HOME|g" "$SEED/options/jdk.table.xml"
grep -q "$OLD_HOME" "$SEED/options/jdk.table.xml" && echo "warning: the seed's SDK paths were not all rewritten" >&2
stop_ours() { pkill -f "IdeScenes --out" >/dev/null 2>&1; pkill -f "fgfast --serve" >/dev/null 2>&1; pkill -f "skia-eval-out/capture/ide/" >/dev/null 2>&1; }   # a stale driver would type into our IDE (2026-11-02)
stop_ours; for i in 1 2 3 4 5 6 7 8; do pgrep -qf "skia-eval-out/capture/ide/" || break; sleep 0.25; done
# a fresh config, system and log per run: the seed is what the IDE sees, indexes are rebuilt (about a minute)
rm -rf "$WORK/ide/$NAME/config" "$WORK/ide/$NAME/log" "$WORK/ide/$NAME/system" "$WORK/ide/$NAME/plugins"
# the scene-driver plugin (scene-driver/build-plugin.sh builds it against the IDE) is the driver's way into the IDE
[ -f "$WORK/plugins/scene-driver/lib/scene-driver.jar" ] || { echo "no scene-driver plugin: run scene-driver/build-plugin.sh" >&2; exit 1; }
mkdir -p "$WORK/ide/$NAME/plugins"; cp -R "$WORK/plugins/." "$WORK/ide/$NAME/plugins/"
# the indexes: a clone of the snapshot when there is one for this IDE build (cp -c: APFS clones, no copying)
BUILD=$(grep -o '"buildNumber": *"[^"]*"' "$APP/Contents/Resources/product-info.json" | sed 's/.*: *"//; s/"$//')
SNAP="$WORK/snapshot/${BUILD:-unknown}/system"
SNAPSHOT_USED=0
if [ -d "$SNAP" ] && [ -z "${REFRESH_SNAPSHOT:-}" ]; then cp -c -R "$SNAP" "$WORK/ide/$NAME/system" && SNAPSHOT_USED=1; fi
# a fresh copy of the scene project (the IDE writes into it; the tracked one stays clean)
PROJECT="$WORK/text-scene"; rm -rf "$PROJECT"; cp -R "$HERE/text-scene" "$PROJECT"
cp "$HERE/seed/project/workspace.xml" "$PROJECT/.idea/workspace.xml"
cp "$HERE/seed/project/src/Hello.java" "$PROJECT/src/Hello.java"
cp "$HERE/seed/project/README.md" "$PROJECT/README.md"
ARMDIR="$OUT/$NAME"; DEST="$ARMDIR/$SEED_THEME-${SCALE}x"; mkdir -p "$DEST"; [ "$THEME" = both ] && mkdir -p "$ARMDIR/dark-${SCALE}x"
# the display must run at the requested scale with room for the 1728x980 frame (the user sets it in the OS;
# this waits and tells them what to set); the frame is placed and captured by the driver at its own bounds
WINDOW="${WINDOW:-1728x980}"
[ "${DISPLAY_CHECKED:-}" = 1 ] || "$JDK/bin/java" -Dapple.awt.UIElement=true -cp "$WORK/classes" ScreenBounds --require "$SCALE" "${WINDOW%x*}" "${WINDOW#*x}" || exit 1
bash "$HERE/run-ide-mac.sh" "$ARM" --scale "$SCALE" --seed "$SEED" -- "$PROJECT" &
LAUNCHER=$!
# the isolated IDE's frame: a java process of ours whose front window names the project
IDE_PID=""
for i in $(seq 1 900); do
  sleep 0.2
  if ! kill -0 $LAUNCHER 2>/dev/null; then echo "the launcher exited before the IDE showed a frame (see above)" >&2; stop_ours; exit 1; fi
  for pid in $(pgrep -f "skia-eval-out/capture/ide/$NAME"); do
    title=$("$WORK/fgfast" --title "$pid" 2>/dev/null)   # the window list, in milliseconds (osascript took 0.4 s per pid)
    case "$title" in text-scene*) IDE_PID=$pid; break;; esac
  done
  [ -n "$IDE_PID" ] && break
done
if [ -z "$IDE_PID" ]; then echo "the isolated IDE showed no frame within 180 s" >&2; kill $LAUNCHER 2>/dev/null; stop_ours; exit 1; fi
echo "isolated IDE pid $IDE_PID"
# indexing over: as many exits from dumb mode as entries (the SDK pre-indexing exits once early, the
# project's indexing enters again — a run of 2026-11-02 started its scenes between the two)
LOG="$WORK/ide/$NAME/log/idea.log"
SMART=0
for i in $(seq 1 1200); do
  sleep 0.2
  ENTERS=$(grep -c "enter dumb mode \[text-scene\]" "$LOG" 2>/dev/null); EXITS=$(grep -c "exit dumb mode \[text-scene\]" "$LOG" 2>/dev/null); ENTERS=${ENTERS:-0}; EXITS=${EXITS:-0}   # grep -c prints 0 itself ("|| echo 0" doubled it once)
  if [ "$EXITS" -ge 1 ] && [ "$EXITS" -ge "$ENTERS" ]; then SMART=$((SMART + 1)); else SMART=0; fi
  [ "$SMART" -ge 3 ] && break   # 0.6 s of smart mode without a new entry
done
[ "$SMART" -ge 3 ] || echo "warning: still in dumb mode after 240 s" >&2
PORT=$(grep -o "built-in server started, port [0-9]*" "$LOG" | head -1 | grep -o "[0-9]*$")
[ -n "$PORT" ] || { echo "the IDE's built-in server port is not in its log" >&2; kill -9 "$IDE_PID"; stop_ours; exit 1; }
# the IDE's post-indexing work (inspections, the project view filling): the driver waits until the
# screen has been unchanged for 2 s (--quiesce; 2026-10-26: a fixed 15 s, then 5 s)
# the driver is a UI element: it must never become the active application (its keys would fall through to another window)
"$JDK/bin/java" -Dapple.awt.UIElement=true -cp "$WORK/classes" IdeScenes --out "$ARMDIR" --pid "$IDE_PID" --fg "$HERE/fg.sh" --helper "$WORK/fgfast" --port "$PORT" --quiesce 1500 --window "$WINDOW" --scale "$SCALE" --themes "$THEMES" ${SCENES:+--scenes "$SCENES"} 2>&1 | tee "$ARMDIR/scenes.log"
DRIVER_STATUS=${PIPESTATUS[0]}
grep -E "Skia rendering|JRE:" "$WORK/ide/$NAME/stdout.txt" "$LOG" 2>/dev/null | head -2 > "$DEST/arm.txt"
[ "$THEME" = both ] && cp "$DEST/arm.txt" "$ARMDIR/dark-${SCALE}x/arm.txt"
# without a snapshot: SIGTERM — the IDE's shutdown hooks flush the indexes (its own exit, asked through the plugin, never
# came, 2026-11-02; the snapshot from a SIGTERM run was valid: no re-indexing) — then keep its system directory
if [ "$SNAPSHOT_USED" = 0 ] && [ "$DRIVER_STATUS" = 0 ]; then
  kill -TERM "$IDE_PID" 2>/dev/null; for i in $(seq 1 120); do kill -0 "$IDE_PID" 2>/dev/null || break; sleep 0.25; done
  if ! kill -0 "$IDE_PID" 2>/dev/null; then rm -rf "$SNAP"; mkdir -p "$(dirname "$SNAP")"; cp -c -R "$WORK/ide/$NAME/system" "$SNAP" && echo "index snapshot saved: $SNAP"; else echo "warning: the IDE did not exit in 30 s, no snapshot" >&2; fi
fi
if ! cmp -s "$HERE/seed/project/src/Hello.java" "$PROJECT/src/Hello.java" || ! cmp -s "$HERE/seed/project/README.md" "$PROJECT/README.md"; then
  echo "INVALID RUN: the project sources were modified during the scenes" | tee "$ARMDIR/INVALID.txt" >&2
fi
# a hard kill: the next run deletes this IDE's config, system and log anyway (a clean exit took 3 s)
kill -9 "$IDE_PID" 2>/dev/null
stop_ours
kill $LAUNCHER 2>/dev/null
if [ "$DRIVER_STATUS" != 0 ]; then echo "FAILED (driver exit $DRIVER_STATUS): $ARMDIR" >&2; exit 1; fi
echo "done: $ARMDIR"
