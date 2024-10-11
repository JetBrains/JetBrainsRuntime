#!/bin/bash

set -euxo pipefail

wget https://raw.githubusercontent.com/GNOME/gtk/refs/heads/main/gdk/wayland/protocol/gtk-shell.xml
# This script creates a Docker image suitable for building musl AArch64 variant

docker build \
  --platform=linux/aarch64 \
  -t registry.jetbrains.team/p/jbre/containers/alpine14_aarch64:latest \
  --no-cache \
  -f Dockerfile.alpine .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
