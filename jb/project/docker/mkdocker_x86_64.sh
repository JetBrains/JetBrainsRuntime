#!/bin/bash

set -euo pipefail
set -x

# This script creates a Docker image suitable for building AArch64 variant
# of the JetBrains Runtime "dev" version.

BOOT_JDK_REMOTE_FILE=zulu21.36.17-ca-jdk21.0.4-linux_x64.tar.gz
BOOT_JDK_SHA=318d0c2ed3c876fb7ea2c952945cdcf7decfb5264ca51aece159e635ac53d544
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
