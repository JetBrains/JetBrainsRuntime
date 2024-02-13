#!/bin/bash

set -euo pipefail
set -x

machine=$(uname -m)

echo "Building image for "$machine" arch"

case "$machine" in
  "aarch64" | "arm64v8" | "arm64")
    arch=arm64v8
    ;;
  "x86_64" | "amd64")
    arch=amd64
    ;;
esac
arch_from=$arch


# This script creates Docker images for testing JBR executables


function build {
  local arch=$1
  local os_name=$2
  local os_version=$3
  local arch_from=${4:-$arch}

  echo "Building image for ${os_name} ${os_version} ${arch}"
  echo "====================================="

  docker build -t jetbrains/runtime:jbrTest_${os_name}${os_version}-$arch \
      --build-arg ARCH="$arch" \
      --build-arg ARCH_FROM=$arch_from \
      --build-arg OS_NAME=${os_name} \
      --build-arg OS_VERSION="${os_version}" \
    -f Dockerfile-xvfb .

  return 0
}

build $arch "ubuntu" "20.04"
if [ "$arch" == "amd64" ]; then
  build "i386" "ubuntu" "20.04" $arch
fi
build $arch "ubuntu" "22.04"
