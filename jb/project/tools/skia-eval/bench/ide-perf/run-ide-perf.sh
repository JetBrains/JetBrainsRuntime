#!/bin/bash
# One IDE performance launch (bench/ide-perf): an arm of capture/run-ide-win.sh on java-design-patterns,
# driven by the IDE's own Performance Testing plugin (the IJ-Perf mechanism: a script of %commands,
# -Dtestscript.filename) and, for the UI scenes, by IdePerfScenes over the scene-driver plugin.
#   run-ide-perf.sh <arm> <typing|ui|warmup> <out dir>
# typing: typing.ijperf (six lines typed at 120 ms a key into Hero.java, Commander.java scrolled three
#         times), the typing latency from the IDE's spans (key press to editor painted);
# ui:     ui.ijperf opens eight files, then IdePerfScenes runs the scenes and exits the IDE;
# warmup: the first launch on a fresh project copy (Maven import, indexing), then the system directory
#         and the project's .idea are kept as the snapshot every later launch starts from.
# Every launch records JFR method timing (dumped on exit), the spans (opentelemetry.json) and the Skia
# backend's statistics (J2D_SKIA_STATS, in stdout.txt); <out dir> receives them with the IDE log.
# Needs: the project clone (prepare.sh), the scene-driver plugin (capture/scene-driver/build-plugin.sh),
# IDE (the install), JDK (the image), SDK_HOME (a JDK for the project), all from build/scripts/ide-env.sh.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
KIT="$(cd "$HERE/../.." && pwd)"
source "$KIT/env.sh"
command -v git > /dev/null || git() { "/c/Program Files/Git/cmd/git.exe" "$@"; }   # the contained MSYS2 has no git: Git for Windows' own
ARM=$1; LAUNCH=$2; OUT=$3
WORK="$OUT_ROOT/ide-perf"
export IDE_STATE_ROOT="$WORK/ide"
PROJECT="$WORK/project"
SNAP="$WORK/snapshot"
NAME=$(grep -oE "^  $ARM\) NAME=[^;]+" "$KIT/capture/run-ide-win.sh" | sed 's/.*NAME=//')
[ -n "$NAME" ] || { echo "unknown arm $ARM" >&2; exit 2; }
STATE="$IDE_STATE_ROOT/$NAME"
[ -d "$PROJECT/.git" ] || { echo "no project at $PROJECT: run prepare.sh" >&2; exit 1; }
mkdir -p "$OUT"
JDK_WIN=$(cygpath -w "$(cd "$JDK" && pwd)")

stop_ours() { powershell -NoProfile -Command "Get-Process java -ErrorAction SilentlyContinue | Where-Object { \$_.Path -like '${JDK_WIN}\\bin\\*' } | Stop-Process -Force" > /dev/null 2>&1; }
stop_ours; sleep 2

# the seed: the kit's (common, Windows, light) and this bench's overrides (no completion popups)
SEED="$WORK/seed"; rm -rf "$SEED"; mkdir -p "$SEED"
cp -r "$KIT/capture/seed/common/." "$KIT/capture/seed/win/." "$KIT/capture/seed/light/." "$HERE/seed/." "$SEED/"
if [ -n "${SDK_HOME:-}" ]; then
  sed -i "s|C:.Users.manuel.unterhofer.AppData.Local.Programs.IntelliJ IDEA 2.jbr|$(cygpath -m "$SDK_HOME")|g" "$SEED/options/jdk.table.xml"
fi

# fresh config, log and plugins; the system directory and the project's state from the snapshot
rm -rf -- "${STATE:?}/config" "${STATE:?}/log" "${STATE:?}/plugins" "${STATE:?}/system"
mkdir -p "$STATE/plugins"
[ -d "$OUT_ROOT/capture/plugins/scene-driver" ] && cp -R "$OUT_ROOT/capture/plugins/." "$STATE/plugins/"
git -C "$PROJECT" checkout -q -- . && git -C "$PROJECT" clean -fdq -e .idea -e '*.iml'
# The project's Maven wrapper stays: the IDE prefers it, and the bundled Maven's server hung on this
# project (2026-11-03, twice, no answer after 9 minutes). The wrapper's Maven lives in ~/.m2/wrapper,
# put there by the first warmup; later launches only read it (checked per run by the series).
if [ "$LAUNCH" != warmup ]; then
  [ -d "$SNAP/system" ] || { echo "no snapshot: run the warmup launch first" >&2; exit 1; }
  cp -R "$SNAP/system" "$STATE/system"
  rm -rf "$PROJECT/.idea"; cp -R "$SNAP/idea" "$PROJECT/.idea"
fi

case "$LAUNCH" in
  typing) SCRIPT="$HERE/typing.ijperf" ;;
  ui) SCRIPT="$HERE/ui.ijperf" ;;
  warmup) SCRIPT="$HERE/warmup.ijperf" ;;
  *) echo "unknown launch $LAUNCH" >&2; exit 2 ;;
esac
STATE_WIN=$(cygpath -w "$STATE")
F='javax.swing.RepaintManager::paintDirtyRegions;java.awt.EventQueue::dispatchEvent;sun.java2d.skia.SKRenderQueue::flushNow;sun.java2d.skia.SKRenderQueue::flushBuffer;sun.java2d.skia.SKRenderQueue::sync;sun.java2d.d3d.D3DRenderQueue::flushNow;sun.java2d.d3d.D3DRenderQueue::flushBuffer;sun.java2d.vulkan.VKRenderQueue::flushNow;sun.java2d.pipe.BufferedTextPipe::drawGlyphList;sun.java2d.pipe.AAShapePipe::renderTiles'
export IDE_EXTRA_VMOPTIONS="-Dtestscript.filename=$(cygpath -w "$SCRIPT")
-Didea.is.integration.test=true
-Didea.diagnostic.opentelemetry.file=$STATE_WIN\\log\\opentelemetry.json
-XX:StartFlightRecording:name=perf,settings=default,dumponexit=true,filename=$STATE_WIN\\log\\perf.jfr,method-timing=$F${EXTRA_VMOPTIONS:+
$EXTRA_VMOPTIONS}"
export J2D_SKIA_STATS=1

START=$(date +%s)
bash "$KIT/capture/run-ide-win.sh" "$ARM" --seed "$SEED" -- "$(cygpath -w "$PROJECT")" &
RUNNER=$!
if [ "$LAUNCH" = ui ]; then
  for i in $(seq 1 600); do
    PORT=$(grep -o "built-in server started, port [0-9]*" "$STATE/log/idea.log" 2>/dev/null | head -1 | grep -o "[0-9]*$")
    [ -n "$PORT" ] && break; sleep 1
  done
  [ -n "${PORT:-}" ] || { echo "no built-in server port in the IDE log" >&2; stop_ours; exit 1; }
  "$JDK/bin/java" -cp "$WORK/classes" IdePerfScenes --port "$PORT" --log "$(cygpath -w "$STATE/log/idea.log")" > "$OUT/scenes.tsv" 2> "$OUT/scenes.err" \
    || echo "scene driver failed: $(head -3 "$OUT/scenes.err")" >&2
fi
# the IDE exits by itself (%exitApp, the driver's exit); 20 minutes is a hung run
for i in $(seq 1 1200); do kill -0 $RUNNER 2>/dev/null || break; sleep 1; done
if kill -0 $RUNNER 2>/dev/null; then echo "the IDE did not exit: stopped" >&2; stop_ours; fi
wait $RUNNER 2>/dev/null
echo "$NAME $LAUNCH: $(( $(date +%s) - START )) s"

cp "$STATE/log/idea.log" "$STATE/stdout.txt" "$OUT/" 2>/dev/null
cp "$STATE/log/opentelemetry.json" "$STATE/log/perf.jfr" "$OUT/" 2>/dev/null
cp "$STATE/idea.vmoptions" "$OUT/" 2>/dev/null
if [ "$LAUNCH" = warmup ]; then
  rm -rf "$SNAP"; mkdir -p "$SNAP"
  cp -R "$STATE/system" "$SNAP/system" && cp -R "$PROJECT/.idea" "$SNAP/idea" && echo "snapshot saved: $SNAP"
fi
