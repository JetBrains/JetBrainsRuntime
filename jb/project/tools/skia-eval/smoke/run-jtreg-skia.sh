#!/bin/bash
# Runs the Skia run lines of the jtreg tests under test/jdk/jb/java/awt/Graphics2D (incl. TextRender)
# as plain java (no jtreg on the VM): run-jtreg-skia.sh <jdk image> [out dir]
# Prints one line per run line: PASS / FAIL, and the test's own summary line.
set -u
R="${R:-$(cd "$(dirname "$0")/../../../../.." && pwd)}"
JDK=$1; OUT="${2:-$R/build/skia-eval-out/jtreg}"
X="--add-exports java.desktop/sun.java2d=ALL-UNNAMED --add-exports java.desktop/sun.java2d.pipe=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.font=ALL-UNNAMED --add-exports java.desktop/sun.java2d.loops=ALL-UNNAMED --add-exports java.desktop/sun.awt.image=ALL-UNNAMED"
mkdir -p "$OUT"; cd "$OUT" || exit 2
pass=0; fail=0
for f in "$R"/test/jdk/jb/java/awt/Graphics2D/*.java "$R"/test/jdk/jb/java/awt/Graphics2D/TextRender/*.java; do
  grep -q "sun.java2d.skia=true" "$f" || continue
  name=$(basename "$f" .java)
  "$JDK/bin/javac.exe" $X -d "$OUT" "$f" 2> "$OUT/$name.javac.txt" || { echo "COMPILE FAIL $name"; sed -n 1,5p "$OUT/$name.javac.txt"; fail=$((fail+1)); continue; }
  grep -E "^\s*\*\s*@run main/othervm .*skia=true" "$f" | sed -E 's/^\s*\*\s*@run main\/othervm //' | while read -r line; do
    log="$OUT/$name.$(echo "$line" | tr -c 'A-Za-z0-9=._-' '_' | cut -c1-120).log"
    if timeout 600 "$JDK/bin/java.exe" $X $line > "$log" 2>&1; then echo "PASS  $line"; else echo "FAIL  $line"; grep -m3 -E "Exception|beyond|ratio|times a frame" "$log" | cut -c1-200; fi
    grep -E "times a frame|ratio" "$log" | head -2 | sed 's/^/      /'
  done
done
