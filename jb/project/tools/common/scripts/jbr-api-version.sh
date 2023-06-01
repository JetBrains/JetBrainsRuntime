#!/bin//bash

FILE="`dirname "$0"`/../../../../../src/java.base/share/classes/com/jetbrains/registry/JBRApiRegistry.java"
TEXT=`cat $FILE`
REGEX='SUPPORTED_VERSION\s*=\s*"([^"]+)"\s*;'

if [[ $TEXT =~ $REGEX ]]; then
    echo "${BASH_REMATCH[1]}"
else
    >&2 echo "Cannot extract JBR API version: regex didn't match."
    exit 1
fi