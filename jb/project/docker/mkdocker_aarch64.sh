#!/bin/bash

set -euo pipefail
set -x

# This script creates a Docker image suitable for building AArch64 variant
# of the JetBrains Runtime "dev" version.

BOOT_JDK_REMOTE_FILE=jbrsdk-21-linux-aarch64-b75.2.tar.gz
BOOT_JDK_SHA=82046e907595455180d423b3fc38fcbb695bb58440c0a9c0e4b5bb8256cb1bc47020a7170e4328a37545bd69faba0f534db4c41ff7f7a60a2cd4dd9083a6bcd8
BOOT_JDK_LOCAL_FILE=boot_jdk.tar.gz

if [ ! -f $BOOT_JDK_LOCAL_FILE ]; then
    # Obtain "boot JDK" from outside of the container.
    wget -nc https://cache-redirector.jetbrains.com/intellij-jbr/${BOOT_JDK_REMOTE_FILE} -O $BOOT_JDK_LOCAL_FILE
else
    echo "boot JDK \"$BOOT_JDK_LOCAL_FILE\" present, skipping download"
fi

# Verify that what we've downloaded can be trusted.
sha512sum -c - <<EOF
$BOOT_JDK_SHA *$BOOT_JDK_REMOTE_FILE
EOF

docker build -t jetbrains/runtime:jbr21env_aarch64 -f Dockerfile.aarch64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
