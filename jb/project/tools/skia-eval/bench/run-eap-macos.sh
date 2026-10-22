#!/bin/bash
# Runs an installed IntelliJ IDEA on a JDK image, as an isolated instance, through the JVM.
# The macOS launcher of the 2026.3 EAP ignores IDEA_JDK and idea.jdk (it falls back to the
# bundled JBR without a message) and exits at once when started from a shell, so the launch
# parameters are taken from product-info.json instead.
# Usage: run-eap.sh <jdk-home> <vmoptions-file> [<app-bundle>] [<state-dir>]
set -eu
JDK=$1; VMOPTIONS=$2
APP=${3:-"$HOME/Applications/IntelliJ IDEA 2026.3 EAP.app"}
STATE=${4:-/tmp/idea-isolated}
mkdir -p "$STATE"/{config,system,log,plugins}
INFO="$APP/Contents/Resources/product-info.json"
python3 - "$APP" "$INFO" "$STATE/jvmargs.txt" <<'PY'
import json, sys
app, info, out = sys.argv[1:]
launch = json.load(open(info))['launch'][0]
args = [a.replace('$APP_PACKAGE', app) for a in launch['additionalJvmArguments']]
open(out, 'w').write('\n'.join('"%s"' % a.replace('"', '\\"') for a in args) + '\n')
PY
CP=$(python3 -c "import json,sys; app,info=sys.argv[1:]; print(':'.join(app+'/Contents/lib/'+j for j in json.load(open(info))['launch'][0]['bootClassPathJarNames']))" "$APP" "$INFO")
exec "$JDK/bin/java" "@$STATE/jvmargs.txt" "@$VMOPTIONS" \
  -Didea.config.path="$STATE/config" -Didea.system.path="$STATE/system" \
  -Didea.log.path="$STATE/log" -Didea.plugins.path="$STATE/plugins" \
  -cp "$CP" com.intellij.idea.Main "$@"
