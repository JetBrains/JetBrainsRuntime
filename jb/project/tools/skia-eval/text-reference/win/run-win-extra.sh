#!/bin/bash
# Extra arms over an existing run-win.sh output: the JDK's own DirectWrite LCD glyph path (JBR's
# -Dsun.java2d.font.lcdGlyphs=directwrite; it applies to LCD strikes only, gray and aliased stay FreeType)
# on the software loops and on the Skia pipe, plus the GDI-classic measuring variant.
#   run-win-extra.sh <out-dir> [more sheet lines via SHEETS env]
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
JDK="${JDK:-$HERE/../windows-aarch64-skia-main/images/jdk}"
FONTS="$HERE/fonts"; OUT=$1
sheet() {
  name=$1; how=$2; shift 2
  flag=""; [ "$how" = volatile ] && flag="--volatile"
  "$JDK/bin/java" -Dsun.java2d.uiScale=1 "$@" -cp "$HERE/classes" TextReference "$OUT/$name" "$OUT/rows-$name.txt" "$FONTS" $flag 2>&1 | grep -v "^Picked up\|^WARNING\|^pipeline" || true
  diff -q --strip-trailing-cr "$OUT/rows-$name.txt" "$OUT/rows.txt" > /dev/null || echo "row order differs: $name" >&2
  rm -f "$OUT/rows-$name.txt"
}
sheet software-dwlcd software -Dsun.java2d.d3d=false -Dsun.java2d.font.lcdGlyphs=directwrite
sheet skia-platform-dwlcd volatile -Dsun.java2d.skia=true -Dsun.java2d.font.lcdGlyphs=directwrite
# measuring mode 1 = GDI classic, rendering mode default, system gamma/contrast/level/geometry
sheet software-dwlcd-gdiclassic software -Dsun.java2d.d3d=false -Dsun.java2d.font.lcdGlyphs=directwrite -Ddirectwrite.font.rendering.options=1:-1:-1:-1:-1:-1
