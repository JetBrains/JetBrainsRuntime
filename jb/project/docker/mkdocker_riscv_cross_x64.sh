#!/bin/bash -x

# This script creates a Docker image suitable for cross building riscv variant
# of the JetBrains Runtime version 17. Host is x86_64

BOOT_JDK_REMOTE_FILE=zulu17.34.19-ca-jdk17.0.3-linux_x64.tar.gz
BOOT_JDK_SHA=caa17c167d045631f9fd85de246bc5313f29cef5ebb1c21524508d3e1196590c
BOOT_JDK_LOCAL_FILE=boot_jdk_amd64.tar.gz

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

docker build -t jbr17buildenv -f Dockerfile.riscv_on_x64 .

# NB: the resulting container can (and should) be used without the network
# connection (--network none) during build in order to reduce the chance
# of build contamination.
