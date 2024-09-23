#!/bin/bash

set -euo pipefail
set -x

# This script creates a Docker image suitable for building AArch64 variant

BOOT_JDK_REMOTE_FILE=https://cdn.azul.com/zulu/bin/zulu21.36.17-ca-jdk21.0.4-linux_aarch64.tar.gz
BOOT_JDK_SHA=da3c2d7db33670bcf66532441aeb7f33dcf0d227c8dafe7ce35cee67f6829c4c
BOOT_JDK_LOCAL_FILE=boot_jdk.tar.gz

if [ ! -f $BOOT_JDK_LOCAL_FILE ]; then
    # Obtain "boot JDK" from outside of the container.
    wget -nc https://cdn.azul.com/zulu/bin/${BOOT_JDK_REMOTE_FILE} -O $BOOT_JDK_LOCAL_FILE
else
    echo "boot JDK \"$BOOT_JDK_LOCAL_FILE\" present, skipping download"
fi

# Verify that what we've downloaded can be trusted.
sha256sum -c - <<EOF
$BOOT_JDK_SHA *$BOOT_JDK_LOCAL_FILE
EOF

docker build -t jetbrains/runtime:jbr21env_oraclelinux8_aarch64 -f Dockerfile.oraclelinux_aarch64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
