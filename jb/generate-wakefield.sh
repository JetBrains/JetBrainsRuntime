#!/bin/bash

if [[ -z "$1" ]]; then
	SCANNER=wayland-scanner
else
	SCANNER="$1"
fi

set -ex

"$SCANNER" client-header src/java.desktop/share/native/libwakefield/protocol/wakefield.xml src/java.desktop/unix/native/libawt_wlawt/wakefield-client-protocol.h
"$SCANNER" private-code src/java.desktop/share/native/libwakefield/protocol/wakefield.xml src/java.desktop/unix/native/libawt_wlawt/wakefield-client-protocol.c
