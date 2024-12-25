#!/bin/bash

set -euxo pipefail

# This script creates a Docker image suitable for building AArch64 variant

docker build \
  --platform=linux/aarch64 \
  -t registry.jetbrains.team/p/jbre/containers/oraclelinux7_aarch64:latest \
  -f Dockerfile.oraclelinux.aarch64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
