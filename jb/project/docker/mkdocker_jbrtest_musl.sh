#!/bin/bash

set -euo pipefail
set -x

machine=$(uname -m)

echo "Building image for "$machine" arch"

case "$machine" in
  "aarch64" | "arm64v8" | "arm64")
    arch=arm64
    arch_from=arm64v8
    os_version_alpine="3.12"
    ;;
  "x86_64" | "amd64")
    arch=amd64
    arch_from=amd64
    os_version_alpine="3.12"
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
    -f Dockerfile-jbrTest.musl .

  return 0
}


function downloadJBR {
  JBR_REMOTE_FILE=$1
  JBR_SHA=$2
  JBR_LOCAL_FILE=jbrsdk_17.tar.gz

  if [ ! -f $JBR_REMOTE_FILE ]; then
      # Obtain "JBR" from outside of the container.
      wget -nc https://cache-redirector.jetbrains.com/intellij-jbr/${JBR_REMOTE_FILE} -O $JBR_REMOTE_FILE
  # Verify that what we've downloaded can be trusted.
#  sha256sum -c - <<EOF
#$JBR_SHA *$JBR_LOCAL_FILE
#EOF
      cp $JBR_REMOTE_FILE $JBR_LOCAL_FILE
  else
      echo "Runtime \"$JBR_LOCAL_FILE\" present, skipping download"
  fi

  return 0
}

if [ "$arch" == "amd64" ]; then
  JBR_REMOTE_FILE=jbrsdk-17.0.10-linux-musl-x64-b1087.23.tar.gz
  JBR_SHA=4424739563c8735e81972a651b6f6851b2c835c6a9d192385b3dc934eecfae86e0a6e853fcea9b2353e71a576c7ff029e5a6836c6d9b7cd98d1c23b42763fd3e
else
  JBR_REMOTE_FILE=jbrsdk-17.0.10-linux-musl-aarch64-b1087.23.tar.gz
  JBR_SHA=936668fd2c7375fa7e728dcf7988eb36f4ea886b95782cd84cb129585c7bec5de5e94072b0c24817b1ec1873b058b6d30c09edf7d1789e031121a7737b687c4f
fi
downloadJBR $JBR_REMOTE_FILE $JBR_SHA

build $arch "alpine" ${os_version_alpine} ${arch_from}