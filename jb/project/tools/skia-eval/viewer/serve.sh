#!/bin/bash
# Serves the viewer's parent directory over http (pixel access in the browser needs an http origin)
# and opens the viewer. Usage: serve.sh [port]   (from any directory; the script finds its own location)
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
# a catalog viewer names the common root of its trees in serve-root (relative to this directory)
[ -f "$HERE/serve-root" ] && ROOT="$(cd "$HERE/$(cat "$HERE/serve-root")" && pwd)"
# default port derived from the root path, so several viewers can be served at once
PORT="${1:-$(( 8700 + $(cksum <<<"$ROOT" | cut -d" " -f1) % 200 ))}"
REL="${HERE#"$ROOT"/}"
URL="http://127.0.0.1:$PORT/$REL/"
# the URL must answer from this root: a server of another root on the same port moves us to the next port
for try in $(seq 1 20); do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$URL" || true)
  [ "$code" = 200 ] && break
  if curl -s -o /dev/null "http://127.0.0.1:$PORT/"; then PORT=$((PORT + 1)); URL="http://127.0.0.1:$PORT/$REL/"; continue; fi
  (cd "$ROOT" && python3 -m http.server "$PORT" --bind 127.0.0.1 >/dev/null 2>&1 &)
  sleep 1
done
echo "$URL"
[ -n "${NO_OPEN:-}" ] && exit 0   # NO_OPEN=1: only serve, do not open a browser
case "$(uname)" in
  Darwin) open "$URL" ;;
  *) (xdg-open "$URL" 2>/dev/null || start "$URL") ;;
esac
