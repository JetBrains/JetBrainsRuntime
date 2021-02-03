#!/bin/bash -x

# The following parameters must be specified:
#   JBSDK_VERSION    - specifies major version of OpenJDK e.g. 11_0_6 (instead of dots '.' underbars "_" are used)
#   JDK_BUILD_NUMBER - specifies update release of OpenJDK build or the value of --with-version-build argument to configure
#   build_number     - specifies the number of JetBrainsRuntime build
#   bundle_type      - specifies bundle to be built; possible values:
#                        jcef - the release bundles with jcef
#                        dcevm - the release bundles with dcevm patches
#                        nomod - the release bundles without any additional modules (jcef)
#                        fd - the fastdebug bundles which also include the jcef module
#
# jbrsdk-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
# jbr-${JBSDK_VERSION}-osx-x64-b${build_number}.tar.gz
#
# $ ./java --version
# openjdk 11.0.6 2020-01-14
# OpenJDK Runtime Environment (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number})
# OpenJDK 64-Bit Server VM (build 11.0.6+${JDK_BUILD_NUMBER}-b${build_number}, mixed mode)
#
# Environment variables:
#   MODULAR_SDK_PATH - specifies the path to the directory where imported modules are located.
#               By default imported modules should be located in ./modular-sdk
#   JCEF_PATH - specifies the path to the directory with JCEF binaries.
#               By default JCEF binaries should be located in ./jcef_mac

JBSDK_VERSION=$1
JDK_BUILD_NUMBER=$2
build_number=$3
bundle_type=$4
architecture=$5 # aarch64 or x64
enable_aot=$6 # temporary param for building test jre with aot under aarch64
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')
WITH_IMPORT_MODULES="--with-import-modules=${MODULAR_SDK_PATH:=./modular-sdk}"
JCEF_PATH=${JCEF_PATH:=./jcef_mac}
architecture=${architecture:=x64}

source jb/project/tools/common.sh

function copyJNF {
  __contents_dir=$1
    # we can't notarize this library as usual framework (with headers and tbd-file)
    # but single library notarizes correctly
    mkdir -p ${__contents_dir}/Frameworks/JavaNativeFoundation.framework/Resources
    cp -p Frameworks/JavaNativeFoundation.framework/JavaNativeFoundation \
      ${__contents_dir}/Frameworks/JavaNativeFoundation.framework || do_exit $?
    cp -p Frameworks/JavaNativeFoundation.framework/Resources/Info.plist \
      ${__contents_dir}/Frameworks/JavaNativeFoundation.framework/Resources || do_exit $?
    # unsign JavaNativeFoundation binary (otherwise notarization will fail)
    codesign --remove-signature ${__contents_dir}/Frameworks/JavaNativeFoundation.framework/JavaNativeFoundation || do_exit $?
}
function create_jbr {

  JBR_BUNDLE=jbr_${bundle_type}

  case "${bundle_type}" in
  "jcef" | "dcevm" | "nomod" | "fd")
    JBR_BASE_NAME=jbr_${bundle_type}-${JBSDK_VERSION}
    ;;
  *)
    echo "***ERR*** bundle was not specified" && do_exit 1
    ;;
  esac
  if [[ "${architecture}" == *aarch64* ]] && [[ "${enable_aot}" != *enable_aot* ]]; then
    # aot isn't supported yet, so remove dependent modules
    echo "Exclude jdk.internal.vm.compiler and jdk.aot (because aot not supported yet)"
    cat modules.list | \
      grep -v "jdk.internal.vm.compiler\|jdk.aot" \
      > modules_tmp.list
  else
    cat modules.list > modules_tmp.list
  fi
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}

  JRE_CONTENTS=${BASE_DIR}/${JBR_BUNDLE}/Contents
  JRE_HOME=${JRE_CONTENTS}/Home
  if [ -d "${JRE_CONTENTS}" ]; then
    rm -rf ${JRE_CONTENTS}
  fi
  mkdir -p ${JRE_CONTENTS}

  JBR=${JBR_BASE_NAME}-osx-${architecture}-b${build_number}

  echo Running jlink....
  ${BASE_DIR}/$JBRSDK_BUNDLE/Contents/Home/bin/jlink \
      --module-path ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/jmods --no-man-pages --compress=2 \
      --add-modules $(xargs < modules_tmp.list | sed s/" "//g) --output ${JRE_HOME}  || do_exit $?
  grep -v "^JAVA_VERSION" ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release | grep -v "^MODULES" >> ${JRE_HOME}/release
  cp -R ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/MacOS ${JRE_CONTENTS}
  cp ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Info.plist ${JRE_CONTENTS}

  rm -rf ${JRE_CONTENTS}/Frameworks || do_exit $?
  if [[ "${architecture}" == *aarch64* ]]; then
    # we can't notarize this library as usual framework (with headers and tbd-file)
    # but single library notarizes correctly
    copyJNF ${JRE_CONTENTS}
  fi
  if [[ "${bundle_type}" == *jcef* ]] || [[ "${bundle_type}" == *dcevm* ]] || [[ "${bundle_type}" == fd ]]; then
    cp -a ${JCEF_PATH}/Frameworks ${JRE_CONTENTS} || do_exit $?
  fi

  echo Creating ${JBR}.tar.gz ...
  rm -rf ${BASE_DIR}/jbr
  cp -R ${BASE_DIR}/${JBR_BUNDLE} ${BASE_DIR}/jbr

  COPYFILE_DISABLE=1 tar -pczf ${JBR}.tar.gz --exclude='*.dSYM' --exclude='man' -C ${BASE_DIR} jbr || do_exit $?
  rm -rf ${BASE_DIR}/${JBR_BUNDLE}
}

JBRSDK_BASE_NAME=jbrsdk-${JBSDK_VERSION}
WITH_DEBUG_LEVEL="--with-debug-level=release"
CONF_ARCHITECTURE=x86_64
if [[ "${architecture}" == *aarch64* ]]; then
  CONF_ARCHITECTURE=aarch64
fi
CONF_NAME=macosx-${CONF_ARCHITECTURE}-normal-server-release

JBSDK=${JBRSDK_BASE_NAME}-osx-${architecture}-b${build_number}
case "$bundle_type" in
  "jcef")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    ;;
  "dcevm")
    HEAD_REVISION=$(git rev-parse HEAD)
    git am jb/project/tools/patches/dcevm/*.patch || do_exit $?
    do_reset_dcevm=1
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    ;;
  "nomod")
    WITH_IMPORT_MODULES=""
    ;;
  "fd")
    git apply -p0 < jb/project/tools/patches/add_jcef_module.patch || do_exit $?
    do_reset_changes=1
    WITH_DEBUG_LEVEL="--with-debug-level=fastdebug"
    CONF_NAME=macosx-${CONF_ARCHITECTURE}-normal-server-fastdebug
    JBSDK=${JBRSDK_BASE_NAME}-osx-${architecture}-fastdebug-b${build_number}
    ;;
  *)
    echo "***ERR*** bundle was not specified" && do_exit 1
    ;;
esac

if [[ "${architecture}" == *aarch64* ]]; then
  # NOTE: aot, cds aren't supported yet
  WITH_JVM_FEATURES="--with-jvm-features=-aot"
  if [[ "${enable_aot}" == *enable_aot* ]]; then
    echo "Enable unstable jvm feature: AOT"
    WITH_JVM_FEATURES=""
  fi
  sh configure \
  --disable-warnings-as-errors \
  $WITH_DEBUG_LEVEL \
  --with-vendor-name="${VENDOR_NAME}" \
  --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
  --with-jvm-features=shenandoahgc \
  --with-version-pre= \
  --with-version-build=${JDK_BUILD_NUMBER} \
  --with-version-opt=b${build_number} \
  $WITH_IMPORT_MODULES \
  --with-boot-jdk=`/usr/libexec/java_home -v 11` \
  --disable-hotspot-gtest --disable-javac-server --disable-full-docs --disable-manpages \
  --enable-cds=no \
  $WITH_JVM_FEATURES \
  --with-extra-cflags="-F$(pwd)/Frameworks" \
  --with-extra-cxxflags="-F$(pwd)/Frameworks" \
  --with-extra-ldflags="-F$(pwd)/Frameworks" || do_exit $?
else
  sh configure \
    --disable-warnings-as-errors \
    $WITH_DEBUG_LEVEL \
    --with-vendor-name="${VENDOR_NAME}" \
    --with-vendor-version-string="${VENDOR_VERSION_STRING}" \
    --with-version-pre= \
    --with-version-build=${JDK_BUILD_NUMBER} \
    --with-version-opt=b${build_number} \
    $WITH_IMPORT_MODULES \
    --with-boot-jdk=`/usr/libexec/java_home -v 11` \
    --enable-cds=yes || do_exit $?
fi

make clean CONF=$CONF_NAME || do_exit $?
make images CONF=$CONF_NAME || do_exit $?

JSDK=build/${CONF_NAME}/images/jdk-bundle

BASE_DIR=jre
JBRSDK_BUNDLE=jbrsdk

rm -rf $BASE_DIR
mkdir $BASE_DIR || do_exit $?
cp -a $JSDK/jdk-$JBSDK_VERSION_WITH_DOTS.jdk $BASE_DIR/$JBRSDK_BUNDLE || do_exit $?
if [[ "${bundle_type}" == *jcef* ]] || [[ "${bundle_type}" == *dcevm* ]] || [[ "${bundle_type}" == fd ]]; then
  cp -a ${JCEF_PATH}/Frameworks $BASE_DIR/$JBRSDK_BUNDLE/Contents/
fi
if [ "${bundle_type}" == "jcef" ] || [ "${bundle_type}" == "fd" ]; then
  echo Creating $JBSDK.tar.gz ...
  if [[ "${architecture}" == *aarch64* ]]; then
    copyJNF $BASE_DIR/$JBRSDK_BUNDLE/Contents
  fi
  sed 's/JBR/JBRSDK/g' ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release > release
  mv release ${BASE_DIR}/${JBRSDK_BUNDLE}/Contents/Home/release
  [ -f "${JBSDK}.tar.gz" ] && rm "${JBSDK}.tar.gz"
  COPYFILE_DISABLE=1 tar -pczf ${JBSDK}.tar.gz -C ${BASE_DIR} \
    --exclude='.DS_Store' --exclude='*~' \
    --exclude='Home/demo' --exclude='Home/man' --exclude='Home/sample' \
    ${JBRSDK_BUNDLE} || do_exit $?
fi

create_jbr || do_exit $?

if [ "$bundle_type" == "jcef" ]; then
  make test-image CONF=$CONF_NAME || do_exit $?

  JBRSDK_TEST=$JBRSDK_BASE_NAME-osx-test-${architecture}-b$build_number

  echo Creating $JBRSDK_TEST.tar.gz ...
  [ -f "${JBRSDK_TEST}.tar.gz" ] && rm "${JBRSDK_TEST}.tar.gz"
  COPYFILE_DISABLE=1 tar -pczf ${JBRSDK_TEST}.tar.gz -C build/${CONF_NAME}/images \
    --exclude='test/jdk/demos' test || do_exit $?
fi

do_exit 0