#!/bin/bash

set -euo pipefail
set -x

# This script creates a Docker image suitable for building musl-x64 variant
# of the JetBrains Runtime version 21.

BOOT_JDK_REMOTE_FILE=zulu20.32.11-ca-jdk20.0.2-linux_musl_x64.tar.gz
BOOT_JDK_SHA=fca5081dd6da847fcd06f5b755e58edae22d6784f21b81bf73da2b538f842c07
BOOT_JDK_LOCAL_FILE=boot_jdk_musl_amd64.tar.gz

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

docker build -t jetbrains/runtime:jbr21env_musl_x64 -f Dockerfile.musl_x64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
