#!/bin/bash
# Runs the installed IntelliJ IDEA (Toolbox, 2026.3 EAP) on the Skia JBR image as an isolated
# instance through the JVM, after the Mac's run-eap.sh: the launch parameters come from
# product-info.json, the state (config, system, log, plugins) lives per arm under
# build/text-quality/ide/<arm>, and the arm's pipeline properties come last so they win.
#
#   run-ide.sh <arm> [--scale N] [--seed <dir>] [--wait S --shot <png>] [-- <idea args, e.g. a project path>]
#     arms: A0 (software loops), A1 blend, A2 twopass, A3 accumulate, R1 Direct3D 9
#     --scale    sun.java2d.uiScale for the IDE (1, 1.5, 2); default: the display's
#     --seed     a config directory copied over the arm's config before the start (theme, fonts, options)
#     --wait/--shot  wait S seconds, capture the screen to <png> (device pixels) and stop the IDE
#
# Without --wait the IDE stays in the foreground until it exits.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-$HERE/../../../../../../build/windows-x86_64-skia/images/jdk}"   # the Skia image to run the IDE on (env JDK overrides)
IDE="${IDE:-$(cygpath -u "$LOCALAPPDATA")/Programs/IntelliJ IDEA}"   # the Toolbox installation (env IDE overrides)
PY="${PY:-python}"   # any Python 3

ARM=$1; shift
SCALE=""; SEED=""; WAIT=""; SHOT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --scale) SCALE=$2; shift 2 ;;
    --seed) SEED=$2; shift 2 ;;
    --wait) WAIT=$2; shift 2 ;;
    --shot) SHOT=$2; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
case "$ARM" in
  A0) NAME=A0-software;   PROPS="-Dsun.java2d.skia=false -Dsun.java2d.d3d=false" ;;
  A1) NAME=A1-blend;      PROPS="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.d3d=false" ;;
  A2) NAME=A2-twopass;    PROPS="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.d3d=false" ;;
  A3) NAME=A3-accumulate; PROPS="-Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.d3d=false" ;;
  R1) NAME=R1-d3d9;       PROPS="-Dsun.java2d.skia=false -Dsun.java2d.d3d=true" ;;
  *) echo "unknown arm $ARM" >&2; exit 2 ;;
esac

STATE="${IDE_STATE_ROOT:-$HERE/ide}/$NAME"   # IDE_STATE_ROOT: where the arms' state lives (default: ide/ next to this script)
mkdir -p "$STATE"/{config,system,log,plugins}
if [ -n "$SEED" ]; then
  cp -r "$SEED"/. "$STATE/config/"
fi
IDE_WIN=$(cygpath -w "$IDE" 2>/dev/null || echo "$IDE" | sed 's|^/c/|C:/|')
STATE_WIN=$(cygpath -w "$STATE" 2>/dev/null || echo "$STATE" | sed 's|^/d/|D:/|')

# launch parameters from product-info.json (IDE_HOME substituted, Windows paths)
"$PY" -I - "$IDE/product-info.json" "$IDE_WIN" "$STATE/jvmargs.txt" "$STATE/classpath.txt" <<'PY'
import json, sys
info, home, out_args, out_cp = sys.argv[1:]
launch = json.load(open(info, encoding='utf-8'))['launch'][0]
args = [a.replace('%IDE_HOME%', home) for a in launch['additionalJvmArguments']]
open(out_args, 'w', encoding='utf-8').write('\n'.join('"%s"' % a.replace('\\', '\\\\') for a in args) + '\n')
open(out_cp, 'w', encoding='utf-8').write(';'.join(home + '\\lib\\' + j for j in launch['bootClassPathJarNames']))
PY
CP=$(cat "$STATE/classpath.txt")

# the arm's vmoptions: the install's, then ours
{
  grep -v '^-Dsun.java2d\|^-Xmx\|^-Dskiko' "$IDE/bin/idea64.exe.vmoptions"
  echo "-Xmx2048m"
  echo "-Dsun.java2d.skia.verbose=true"
  echo "-Dsun.java2d.uiScale.enabled=true"
  [ -n "$SCALE" ] && echo "-Dsun.java2d.uiScale=$SCALE"
  echo "-Didea.initially.ask.config=never"
  echo "-Didea.trust.all.projects=true"
  echo "-Didea.fatal.error.notification=disabled"
  echo "-Dide.show.tips.on.startup.default.value=false"
  echo "-Djb.consents.confirmation.enabled=false"
  echo "-Didea.suppress.statistics.report=true"
  echo "-Deap.require.license=false"
  echo "-Dide.native.launcher=true"
  echo "-Didea.jre.check=false"
  echo "-Didea.is.internal=false"
  for p in $PROPS; do echo "$p"; done
} > "$STATE/idea.vmoptions"

echo "$NAME: IDE $IDE_WIN, JDK $JDK, state $STATE_WIN"
"$JDK/bin/java" "@$STATE/jvmargs.txt" "@$STATE/idea.vmoptions" \
  -Didea.config.path="$STATE_WIN\\config" -Didea.system.path="$STATE_WIN\\system" \
  -Didea.log.path="$STATE_WIN\\log" -Didea.plugins.path="$STATE_WIN\\plugins" \
  -cp "$CP" com.intellij.idea.Main "$@" > "$STATE/stdout.txt" 2>&1 &
PID=$!
if [ -z "$WAIT" ]; then
  wait $PID
  exit $?
fi
echo "pid $PID, waiting ${WAIT}s"
sleep "$WAIT"
if [ -n "$SHOT" ]; then
  "$JDK/bin/java" -cp "${SMOKE_CLASSES:-$HERE/../skia-smoke/out}" ScreenShot "$SHOT" 0
fi
echo "--- idea.log: JRE, Skia, errors"
grep -E "JRE:|Skia|ERROR" "$STATE/log/idea.log" 2>/dev/null | grep -v "\[cds\]" | head -8
echo "--- stdout: Skia line, native warnings"
grep -iE "Skia rendering|\[E\]|\[W\]|device lost|Exception" "$STATE/stdout.txt" | head -8
taskkill //PID $PID //T //F > /dev/null 2>&1 || true
echo "stopped"
