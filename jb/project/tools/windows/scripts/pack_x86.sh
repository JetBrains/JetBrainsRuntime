#!/bin/bash

set -euo pipefail
set -x

# The following parameters must be specified:
#   build_number - specifies the number of JetBrainsRuntime build
#   bundle_type  - specifies bundle to be built;possible values:
#               <empty> or nomod - the release bundles without any additional modules (jcef)
#               fd - the fastdebug bundles which also include the jcef module
#

source jb/project/tools/common/scripts/common.sh

[ "$bundle_type" == "jcef" ] && echo "not implemented" && do_exit 1

function pack_jbr {
  __bundle_name=$1
  __arch_name=$2

  fastdebug_infix=''

  [ "$bundle_type" == "fd" ] && [ "$__arch_name" == "$JBRSDK_BUNDLE" ] && __bundle_name=$__arch_name && fastdebug_infix="fastdebug-"
  JBR=${__bundle_name}-${JBSDK_VERSION}-windows-x86-${fastdebug_infix}b${build_number}
  __root_dir=${__bundle_name}-${JBSDK_VERSION}-windows-x86-${fastdebug_infix}b${build_number}

  echo Creating $JBR.tar.gz ...
  chmod -R ug+rwx,o+rx ${BASE_DIR}/$__root_dir
  /usr/bin/tar -czf $JBR.tar.gz -C $BASE_DIR $__root_dir || do_exit $?
}

[ "$bundle_type" == "nomod" ] && bundle_type=""

JBRSDK_BUNDLE=jbrsdk
BASE_DIR=.

if [ "$bundle_type" == "jcef" ] || [ "$bundle_type" == "dcevm" ] || [ "$bundle_type" == "fd" ]; then
  jbr_name_postfix="_${bundle_type}"
else
  jbr_name_postfix=""
fi

pack_jbr jbr${jbr_name_postfix} jbr
pack_jbr jbrsdk${jbr_name_postfix} jbrsdk

if [ $do_maketest -eq 1 ]; then
  JBRSDK_TEST=$JBRSDK_BUNDLE-$JBSDK_VERSION-windows-test-x86-b$build_number
  echo Creating $JBRSDK_TEST.tar.gz ...
  /usr/bin/tar -czf $JBRSDK_TEST.tar.gz -C $BASE_DIR --exclude='test/jdk/demos' test || do_exit $?
fi