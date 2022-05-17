#!/bin/bash

set -euo pipefail
set -x

# This script creates a Docker image suitable for building musl AArch64 variant
# of the JetBrains Runtime version 17.

BOOT_JDK_REMOTE_FILE=zulu17.32.13-ca-jdk17.0.2-linux_musl_aarch64.tar.gz
BOOT_JDK_SHA=6b920559abafbe9bdef386a20ecf3a2f318bc1f0d8359eb1f95aee26606bbc70
BOOT_JDK_LOCAL_FILE=boot_jdk_musl_aarch64.tar.gz

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

docker build -t jbr17buildenv -f Dockerfile.musl_aarch64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
