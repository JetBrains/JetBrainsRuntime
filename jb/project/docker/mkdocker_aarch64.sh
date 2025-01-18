#!/bin/bash

set -euxo pipefail

# This script creates a Docker image suitable for building AArch64 variant

docker build --platform=linux/aarch64 -t jetbrains/runtime:oraclelinux8_aarch64 -f Dockerfile.oraclelinux .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
