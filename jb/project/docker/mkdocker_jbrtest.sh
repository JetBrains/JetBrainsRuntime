#!/bin/bash

set -euo pipefail
set -x

machine=$(uname -m)

echo "Building image for "$machine" arch"

case "$machine" in
  "aarch64" | "arm64v8" | "arm64")
    arch=arm64
    arch_from=arm64v8
    ;;
  "x86_64" | "amd64")
    arch=amd64
    arch_from=amd64
    ;;
esac


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
    -f Dockerfile-jbrTest .

  return 0
}


function downloadJBR {
  JBR_REMOTE_FILE=jbr-11_0_16-linux-x86-b2043.64.tar.gz
  JBR_SHA=d3e4d488bb8e0edf4fa0832a4ff5b5dcac69e646640ae06afb1b49e419536762
  JBR_LOCAL_FILE=$JBR_REMOTE_FILE

  if [ ! -f $JBR_LOCAL_FILE ]; then
      # Obtain "JBR" from outside of the container.
      curl -o $JBR_LOCAL_FILE https://cache-redirector.jetbrains.com/intellij-jbr/${JBR_REMOTE_FILE} -O
  else
      echo "Runtime \"$JBR_LOCAL_FILE\" present, skipping download"
  fi

  # Verify that what we've downloaded can be trusted.
  sha256sum -c - <<EOF
$JBR_SHA *$JBR_LOCAL_FILE
EOF

  return 0
}

build $arch "ubuntu" "20.04" ${arch_from}
if [ "$arch" == "amd64" ]; then
  downloadJBR
  build "i386" "ubuntu" "20.04" $arch
fi
build $arch "ubuntu" "22.04" ${arch_from}
