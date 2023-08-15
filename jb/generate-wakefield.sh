#!/bin/bash

set -ex

wayland-scanner client-header src/java.desktop/share/native/libwakefield/protocol/wakefield.xml src/java.desktop/unix/native/libawt_wlawt/wakefield-client-protocol.h
wayland-scanner private-code src/java.desktop/share/native/libwakefield/protocol/wakefield.xml src/java.desktop/unix/native/libawt_wlawt/wakefield-client-protocol.c
