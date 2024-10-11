#!/bin/bash

set -euxo pipefail

wget https://raw.githubusercontent.com/GNOME/gtk/refs/heads/main/gdk/wayland/protocol/gtk-shell.xml
# This script creates a Docker image suitable for building x64 variant

docker build --platform=linux/amd64 -t jetbrains/runtime:oraclelinux8_x64 -f Dockerfile.oraclelinux .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
