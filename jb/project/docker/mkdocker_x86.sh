#!/bin/bash

set -euxo pipefail

# This script creates a Docker image suitable for building x86 variant

docker build --platform=linux/i386 -t jetbrains/runtime:ubuntu2004_x86 -f Dockerfile.ubuntu.x86 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
