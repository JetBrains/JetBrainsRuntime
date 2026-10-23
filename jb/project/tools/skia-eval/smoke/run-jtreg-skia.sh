#!/bin/bash
# Runs the Skia run lines of the jtreg tests under test/jdk/jb/java/awt/Graphics2D (incl. TextRender)
# as plain java (no jtreg on the VM): run-jtreg-skia.sh <jdk image> [out dir]
# Prints one line per run line: PASS / FAIL, and the test's own summary line.
# Stands in for jtreg's @library .. (PipelineComparison, via -sourcepath) and @modules (the exports
# below, and sun.font opened at run time; /test/lib via -sourcepath and -Dtest.jdk).
set -u
R="${R:-$(cd "$(dirname "$0")/../../../../.." && pwd)}"
JDK=$(cd "$1" && pwd); OUT="${2:-$R/build/skia-eval-out/jtreg}"
JDKW=$(cd "$JDK" && (pwd -W 2>/dev/null || pwd)); RW=$(cd "$R" && (pwd -W 2>/dev/null || pwd))   # Windows paths: javac's source path list, the test library's JDK
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd) || exit 2; cd "$OUT"   # absolute: the logs are written after the cd
OUTW=$(pwd -W 2>/dev/null || pwd)   # Windows path for the property (a POSIX one would land in C:/c/...)
G="$R/test/jdk/jb/java/awt/Graphics2D"
X="--add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.java2d.pipe=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.font=ALL-UNNAMED --add-exports java.desktop/sun.java2d.loops=ALL-UNNAMED --add-exports java.desktop/sun.awt.image=ALL-UNNAMED --add-exports java.desktop/sun.java2d.skia=ALL-UNNAMED"
XR="$X --add-opens java.desktop/sun.font=ALL-UNNAMED -Dtest.jdk=$JDKW -Dsun.java2d.skia.pipelineCache=$OUTW/pipeline-cache"   # the cache stays out of the home directory
pass=0; fail=0
for f in "$G"/*.java "$G"/TextRender/*.java; do
  grep -q "sun.java2d.skia=true" "$f" || continue
  name=$(basename "$f" .java)
  "$JDK/bin/javac.exe" $X -sourcepath "$RW/test/jdk/jb/java/awt/Graphics2D;$RW/test/lib" -d "$OUT" "$f" 2> "$OUT/$name.javac.txt" || { echo "COMPILE FAIL $name"; sed -n 1,5p "$OUT/$name.javac.txt"; fail=$((fail+1)); continue; }
  grep -E "^\s*\*\s*@run main/othervm(/timeout=[0-9]+)? .*skia=true" "$f" | sed -E 's/^\s*\*\s*@run main\/othervm(\/timeout=[0-9]+)? //' | while read -r line; do
    log="$OUT/$name.$(echo "$line" | tr -c 'A-Za-z0-9=._-' '_' | cut -c1-120).log"
    if timeout 600 "$JDK/bin/java.exe" $XR -cp "$OUT" $line > "$log" 2>&1; then echo "PASS  $line"; else echo "FAIL  $line"; grep -m3 -E "Exception|beyond|ratio|times a frame" "$log" | cut -c1-200; fi
    grep -E "times a frame|ratio" "$log" | head -2 | sed 's/^/      /'
  done
done
