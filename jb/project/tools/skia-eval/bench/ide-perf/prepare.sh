#!/bin/bash
# Prepares the IDE performance runs (bench/ide-perf): a local clone of java-design-patterns (the
# IJ-Perf typing tests' project) under build/skia-eval-out/ide-perf/project, its Maven settings kept
# inside the build directory (the project's Maven wrapper, the local repository under ide-perf/m2: nothing in
# ~/.m2 but the wrapper's own Maven, see run-ide-perf.sh), and the scene driver compiled. Then run the warmup launch once:
#   prepare.sh <java-design-patterns clone>     (e.g. ~/Projects/java-design-patterns)
#   run-ide-perf.sh A2 warmup <out dir>
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../../env.sh"
command -v git > /dev/null || git() { "/c/Program Files/Git/cmd/git.exe" "$@"; }   # the contained MSYS2 has no git: Git for Windows' own
SRC=$1
WORK="$OUT_ROOT/ide-perf"
PROJECT="$WORK/project"
mkdir -p "$WORK/m2" "$WORK/classes"
rm -rf "$PROJECT"
git clone -q --local "$SRC" "$PROJECT"
mkdir -p "$PROJECT/.idea"
M2=$(cygpath -m "$WORK/m2")
cat > "$PROJECT/.idea/workspace.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="MavenImportPreferences">
    <option name="generalSettings">
      <MavenGeneralSettings>
        <option name="localRepository" value="$M2" />
        <option name="mavenHomeTypeForPersistence" value="WRAPPER" />
        <option name="workOffline" value="true" />
      </MavenGeneralSettings>
    </option>
  </component>
</project>
EOF
"$JDK/bin/javac" -d "$WORK/classes" "$HERE/IdePerfScenes.java"
echo "project $PROJECT ($(git -C "$PROJECT" log --oneline -1)), Maven repository $M2"
