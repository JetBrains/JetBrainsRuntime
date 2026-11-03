#!/bin/bash
# The installed IntelliJ IDEA 2026.3 EAP on the Skia JBR image as an isolated instance through the
# JVM (after build/skia-bench/idea/run-eap.sh), one state directory per arm under ide/<arm>.
#   run-ide.sh <arm> [--scale N] [--seed <dir>] [-- <idea args, e.g. a project path>]
#   arms: M0 Metal (today's IDE), T0 Skia pipe + platform scaler, T1 Skia pipe + Skia scaler (coverage),
#         T2 Skia scaler, grayscale darkened, T3 Skia scaler, grayscale smoothed
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../env.sh"
# the IDE: APP, else the newest 2026.3 app the Toolbox installed (it renames EAP → Release Candidate → release), else the stable one
if [ -z "${APP:-}" ]; then APP=$(ls -td "$HOME/Applications/IntelliJ IDEA 2026.3"*.app 2>/dev/null | head -1); fi
APP="${APP:-$HOME/Applications/IntelliJ IDEA.app}"
ARM=$1; shift
SCALE=""; SEED=""
while [ $# -gt 0 ]; do
  case "$1" in
    --scale) SCALE=$2; shift 2 ;;
    --seed) SEED=$2; shift 2 ;;
    --) shift; break ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
# an arm code ending in "f" is the arm with fractional glyph placement everywhere (the IDE's registry keys
# ide.text.fractional.metrics and editor.text.fractional.metrics, which the Registry reads from system
# properties too): the same arm, its captures filed under <theme>-<scale>x-fractional — a sample condition of the viewer (Windows; macOS places fractionally by default)
# the keys are multi-option registries ([Auto*|Disabled|Enabled]): the Registry takes a system property as a choice only
# in the bracket form with the chosen option starred (the VM's finding, 2026-11-03; a plain "Enabled" fell through to Auto)
PLACEMENT=""; case "$ARM" in *f) ARM="${ARM%f}"; PLACEMENT="-Dide.text.fractional.metrics=[Auto|Disabled|Enabled*] -Deditor.text.fractional.metrics=[Auto|Disabled|Enabled*]" ;; esac
case "$ARM" in
  M0) NAME=M0-metal;        PROPS="-Dsun.java2d.metal=true" ;;
  M1) NAME=M1-default;      PROPS="" ;;   # no Java2D property at all (Metal by default)
  T0m) NAME=T0m-skia-metalprop; PROPS="-Dsun.java2d.skia=true -Dsun.java2d.metal=true" ;;   # T0 plus the explicit Metal property
  T0) NAME=T0-skia-platform; PROPS="-Dsun.java2d.skia=true" ;;
  T1) NAME=T1-skia-scaler;   PROPS="-Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia" ;;
  T2) NAME=T2-skia-darkened; PROPS="-Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=darkened" ;;
  T3) NAME=T3-skia-smoothed; PROPS="-Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=smoothed" ;;
  T5) NAME=T5-skia-cg;       PROPS="-Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg" ;;   # Skia pipe, CoreGraphics-rasterized glyphs per text luminance
  F5) NAME=F5-skia-full-cg;  PROPS="-Dsun.java2d.skia=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.skia.text=paragraph" ;;   # the full Skia text route: shaper, paragraph engine, Skia's text path, CoreGraphics masks
  M5) NAME=M5-metal-cg-plain; PROPS="-Dsun.java2d.metal=true -Dsun.java2d.font.scaler=skia -Dsun.java2d.skia.text.gray=cg -Dsun.java2d.metal.textGammaCorrection=false" ;;   # the hybrid: Metal pipe, the same glyphs, plain blend
  *) echo "unknown arm $ARM" >&2; exit 2 ;;
esac
if [ -n "$PLACEMENT" ]; then PROPS="$PROPS $PLACEMENT"; fi   # the captures land in <theme>-<scale>x-fractional (run-scenes-mac.sh); the arm keeps its name
STATE="$OUT_ROOT/capture/ide/$NAME"
mkdir -p "$STATE"/{config,system,log,plugins}
if [ -n "$SEED" ]; then
  cp -R "$SEED"/. "$STATE/config/"
fi
INFO="$APP/Contents/Resources/product-info.json"
python3 -I - "$APP" "$INFO" "$STATE/jvmargs.txt" <<'PY'
import json, sys
app, info, out = sys.argv[1:]
launch = json.load(open(info))['launch'][0]
args = [a.replace('$APP_PACKAGE', app) for a in launch['additionalJvmArguments']]
open(out, 'w').write('\n'.join('"%s"' % a.replace('"', '\\"') for a in args) + '\n')
PY
CP=$(python3 -c "import json,sys; app,info=sys.argv[1:]; print(':'.join(app+'/Contents/lib/'+j for j in json.load(open(info))['launch'][0]['bootClassPathJarNames']))" "$APP" "$INFO")
{
  grep -v '^-Dsun.java2d\|^-Xmx\|^-Dskiko\|^#' "$APP/Contents/bin/idea.vmoptions"
  echo "-Xmx2048m"
  echo "-Dsun.java2d.skia.verbose=true"
  echo "-Dsun.java2d.uiScale.enabled=true"
  # --scale no longer sets sun.java2d.uiScale (2026-11-02): the display runs at the scale itself, the
  # wrapper checks it (ScreenBounds --require), so decorations and the OS's text settings are native
  echo "-Didea.initially.ask.config=never"
  echo "-Didea.trust.all.projects=true"
  echo "-Didea.fatal.error.notification=disabled"
  echo "-Dide.show.tips.on.startup.default.value=false"
  echo "-Djb.consents.confirmation.enabled=false"
  echo "-Didea.suppress.statistics.report=true"
  echo "-Deap.require.license=false"
  echo "-Didea.jre.check=false"
  echo "-Didea.is.internal=false"
  set -f; for p in $PROPS ${EXTRA_PROPS:-}; do echo "$p"; done; set +f   # EXTRA_PROPS: ad-hoc properties for experiments; -f: no globbing of the placement option's "*"
} > "$STATE/idea.vmoptions"
echo "$NAME: JDK $JDK, state $STATE"
exec "$JDK/bin/java" "@$STATE/jvmargs.txt" "@$STATE/idea.vmoptions" \
  -Didea.config.path="$STATE/config" -Didea.system.path="$STATE/system" \
  -Didea.log.path="$STATE/log" -Didea.plugins.path="$STATE/plugins" \
  -cp "$CP" com.intellij.idea.Main "$@" > "$STATE/stdout.txt" 2>&1
