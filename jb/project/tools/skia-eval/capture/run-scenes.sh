#!/bin/bash
# The IDE scene capture, dispatched to the platform's wrapper:  run-scenes.sh <arm> <light|dark|both> <scale> [out-dir] [scenes]
HERE="$(cd "$(dirname "$0")" && pwd)"
case "$(uname -s)" in
  Darwin) exec bash "$HERE/run-scenes-mac.sh" "$@" ;;
  *)      exec bash "$HERE/run-scenes-win.sh" "$@" ;;
esac
