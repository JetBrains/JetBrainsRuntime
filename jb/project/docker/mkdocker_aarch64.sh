#!/bin/bash -x

# This script creates a Docker image suitable for building AArch64 variant
# of the JetBrains Runtime "dev" version.

BOOT_JDK_REMOTE_FILE=zulu17.30.15-ca-jdk17.0.1-linux_aarch64.tar.gz
BOOT_JDK_SHA=4d9c9116eb0cdd2d7fb220d6d27059f4bf1b7e95cc93d5512bd8ce3791af86c7
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

docker build -t jbrdevenv_arm64v8 -f Dockerfile.aarch64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
