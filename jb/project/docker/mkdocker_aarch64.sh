#!/bin/bash

set -euo pipefail

# This script creates a Docker image suitable for building AArch64 variant

echo "ULN username:";
read username;

echo "ULN password:";
read -s password;

echo "CSI:";
read csi;

docker build \
  --platform=linux/aarch64 \
  -t registry.jetbrains.team/p/jbre/containers/oraclelinux7_aarch64:latest \
  --build-arg profilename=OL7_aarch64 \
  --build-arg username=$username \
  --build-arg password=$password \
  --build-arg csi=$csi \
  -f Dockerfile.oraclelinux.aarch64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
