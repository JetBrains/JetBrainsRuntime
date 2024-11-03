#!/bin/bash

#immediately exit script with an error if a command fails
set -euo pipefail
[[ "${SCRIPT_VERBOSE:-}" == "1" ]] && set -x

APP_PATH=$1

if [[ -z "$APP_PATH" ]]; then
  echo "Usage: $0 AppPath"
  exit 1
fi
if [[ ! -f "$APP_PATH" ]]; then
  echo "AppName '$APP_PATH' does not exist or not a file"
  exit 1
fi

function log() {
  echo "$(date '+[%H:%M:%S]') $*"
}


# check required parameters
: "${APPLE_ISSUER_ID}"
: "${APPLE_KEY_ID}"
: "${APPLE_PRIVATE_KEY}"

# shellcheck disable=SC2064
trap "rm -f \"$PWD/tmp_key\"" INT EXIT RETURN
echo -n "${APPLE_PRIVATE_KEY}" > tmp_key

log "Notarizing $APP_PATH..."
xcrun notarytool submit --key tmp_key --key-id "${APPLE_KEY_ID}" --issuer "${APPLE_ISSUER_ID}" "$APP_PATH" 2>&1 --wait| tee "notarytool.submit.out"
REQUEST_ID="$(grep -e " id: " "notarytool.submit.out" | grep -oE '([0-9a-f-]{36})'| head -n1)"

waitOutput=$(xcrun notarytool wait "$REQUEST_ID" --key tmp_key --key-id "${APPLE_KEY_ID}" --issuer "${APPLE_ISSUER_ID}" --timeout 6h)
if [ $? -ne 0 ]; then
  log "Notarizing failed (wait command)"
  echo "$waitOutput"
  exit 1
else
  echo "$waitOutput"
fi

logOutout=$(xcrun notarytool log  "$REQUEST_ID" --key tmp_key --key-id "${APPLE_KEY_ID}" --issuer "${APPLE_ISSUER_ID}" developer_log.json)
if [ $? -ne 0 ]; then
  log "Notarizing failed (log command)"
  echo "$logOutout"
  exit 1
else
  echo "$logOutout"
fi

infoOUtput=$(xcrun notarytool info "$REQUEST_ID" --key tmp_key --key-id "${APPLE_KEY_ID}" --issuer "${APPLE_ISSUER_ID}")
if [ $? -ne 0 ]; then
  log "Notarizing failed (info command)"
  echo "$infoOUtput"
  exit 1
else
  echo "$infoOUtput"
fi

log "Notarizing finished"
