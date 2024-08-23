#!/bin/bash

set -euo pipefail
set -x

# This script creates a Docker image suitable for building musl AArch64 variant
# of the JetBrains Runtime version 21.

BOOT_JDK_REMOTE_FILE=zulu20.32.11-ca-jdk20.0.2-linux_musl_aarch64.tar.gz
BOOT_JDK_SHA=eec57cf744c2438f695221f041d4804de3033ad33b6dba769d3359813ba3f90d
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

docker build -t jetbrains/runtime:jbr21env_musl_aarch64 -f Dockerfile.musl_aarch64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
