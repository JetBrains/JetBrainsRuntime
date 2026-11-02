#!/bin/bash
# The browser's rendering of the text samples as a reference arm (the Mac's chrome arm): render-edge.sh <out-dir>
# writes <out-dir>/chromium/gray/fractional/{1x,1.25x,1.5x,2x}/{on-white,on-dark}.png with headless Edge (Chromium:
# Blink -> Skia on the DirectWrite host; Chrome is not installed on this VM) drawing index-win.html's canvas.
# Fonts come from the JDK image via file URLs; the system families are Segoe UI and Consolas.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$1"
E="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
[ -x "$E" ] || { echo "no Edge at $E" >&2; exit 1; }
PAGE="file:///$(cygpath -m "$HERE/index-win.html")"
for scale in 1 1.25 1.5 2; do for paper in black-on-white white-on-black; do
  d="$OUT/chromium/gray/fractional/${scale}x"; mkdir -p "$d"
  w=$(python -I -c "print(round(460*$scale))"); h=$(python -I -c "print(round(28*$scale)*20)")
  "$E" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files --force-device-scale-factor=1 \
       --window-size="$w,$h" --virtual-time-budget=4000 --screenshot="$(cygpath -w "$d/$paper.png")" \
       "$PAGE?scale=$scale&paper=$paper" >/dev/null 2>&1 || true
done; done
echo "chromium arm (Edge): $("$E" --version 2>/dev/null | head -1)"
