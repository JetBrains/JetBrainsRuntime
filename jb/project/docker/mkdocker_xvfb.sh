#!/bin/bash

set -euo pipefail
set -x

machine=$(uname -m)

case "$machine" in
  "aarch64" | "arm64v8" | "arm64")
    arch=arm64v8
    ;;
  "x86_64" | "amd64")
    arch=amd64""
    ;;
esac


# This script creates Docker images for testing JBR executables


function build {
  arch=$1
  os_name=$2
  os_version=$3

  docker build -t jetbrains/runtime:jbrTest_${os_name}${os_version}-$arch \
      --build-arg ARCH=$arch \
      --build-arg OS_NAME=${os_name} \
      --build-arg OS_VERSION="${os_version}" \
    -f Dockerfile-xvfb .

  return 0
}

build $arch "ubuntu" "20.04"
build $arch "ubuntu" "22.04"
