#!/bin/bash
# Builds the scene driver plugin against the IDE the scenes run in and installs it into the seed:
#   build-plugin.sh            (macOS: the newest IntelliJ IDEA 2026.3 app, or APP=...; Windows: IDE_HOME=<install dir>)
# The jar is compiled against that IDE's libraries (RestService, the terminal API), so build it on each
# machine against its own IDE; it lands in build/skia-eval-out/capture/plugins/scene-driver/lib/scene-driver.jar,
# which run-scenes.sh copies into the isolated IDE's plugins directory.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../../env.sh"
if [ -n "${IDE_HOME:-}" ]; then LIB="$IDE_HOME/lib"; TERM_LIB="$IDE_HOME/plugins/terminal/lib"
else
  if [ -z "${APP:-}" ]; then APP=$(ls -td "$HOME/Applications/IntelliJ IDEA 2026.3"*.app 2>/dev/null | head -1); fi
  APP="${APP:-$HOME/Applications/IntelliJ IDEA.app}"; LIB="$APP/Contents/lib"; TERM_LIB="$APP/Contents/plugins/terminal/lib"
fi
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
OUT="$OUT_ROOT/capture/plugin-build"; rm -rf "$OUT"; mkdir -p "$OUT/META-INF"
"$JDK/bin/javac" --release 21 -nowarn -proc:none -cp "$LIB/*$SEP$TERM_LIB/*" -d "$OUT" "$HERE/SceneService.java"
cp "$HERE/plugin.xml" "$OUT/META-INF/plugin.xml"
DEST="$OUT_ROOT/capture/plugins/scene-driver/lib"; rm -rf "$OUT_ROOT/capture/plugins/scene-driver"; mkdir -p "$DEST"
(cd "$OUT" && "$JDK/bin/jar" cf "$DEST/scene-driver.jar" .)
echo "built $DEST/scene-driver.jar against $LIB"
