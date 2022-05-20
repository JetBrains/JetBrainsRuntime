#!/bin/bash -x

# This script creates a Docker image suitable for building x86 variant
# of the JetBrains Runtime version 17.

BOOT_JDK_REMOTE_FILE=zulu17.34.19-ca-jdk17.0.3-linux_i686.tar.gz
BOOT_JDK_SHA=1c35c374ba0001e675d6e80819d5be900c4e141636d5e484992a8c550be14481
BOOT_JDK_LOCAL_FILE=boot_jdk_x86.tar.gz

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

docker build -t jetbrains/runtime:jbr17env_x86 -f Dockerfile.x86 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
