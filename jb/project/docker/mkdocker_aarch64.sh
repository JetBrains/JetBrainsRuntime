#!/bin/bash

set -euo pipefail

wget https://raw.githubusercontent.com/GNOME/gtk/refs/heads/main/gdk/wayland/protocol/gtk-shell.xml
# This script creates a Docker image suitable for building AArch64 variant

docker build \
  --platform=linux/aarch64 \
  -t registry.jetbrains.team/p/jbre/containers/oraclelinux8_aarch64:latest \
  --no-cache \
  -f Dockerfile.oraclelinux .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
