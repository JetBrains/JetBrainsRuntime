#!/bin/bash
# Chrome's rendering of the text samples as a reference arm: render.sh <out-dir> writes
# <out-dir>/chrome/gray/fractional/{1x,2x}/{black-on-white,white-on-black}.png with headless Chrome drawing
# index.html's canvas (Blink → Skia on the CoreText host). Fonts come from the JDK image via file URLs.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$1"
C="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
[ -x "$C" ] || { echo "no Chrome at $C" >&2; exit 1; }
for scale in 1 2; do for paper in black-on-white white-on-black; do
  d="$OUT/chrome/gray/fractional/${scale}x"; mkdir -p "$d"
  "$C" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files --force-device-scale-factor=1 \
       --window-size=$((460*scale)),$((560*scale)) --virtual-time-budget=4000 --screenshot="$d/$paper.png" \
       "file://$HERE/index.html?scale=$scale&paper=$paper" >/dev/null 2>&1
done; done
echo "chrome arm: $("$C" --version)"
