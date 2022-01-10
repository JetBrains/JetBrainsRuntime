#!/bin/bash -x

tag_prefix="jdk-"

while getopts ":i?" o; do
    case "${o}" in
        i)
            i="incremental build"
            INC_BUILD=1
            ;;
    esac
done
shift $((OPTIND-1))

if [ $# -le $min_parameters_count ]; then
  build_number=$1
  bundle_type=$2
  architecture=$3 # aarch64 or x64
else
  JBSDK_VERSION=$1
  JDK_BUILD_NUMBER=$2
  build_number=$3
  bundle_type=$4
  architecture=$5 # aarch64 or x64
fi

OPENJDK_TAG=$(git log --simplify-by-decoration --decorate=short --pretty=short | grep "$tag_prefix" | cut -d "(" -f2 | cut -d ")" -f1 | awk '{print $2}' | sort -t "-" -k 2 -g | tail -n 1)
JBSDK_VERSION=${JBSDK_VERSION:=$(echo $OPENJDK_TAG | awk -F "-|[+]" '{print $2}')}
echo "##teamcity[setParameter name='env.JBSDK_VERSION' value='${JBSDK_VERSION}']"
JDK_BUILD_NUMBER=${JDK_BUILD_NUMBER:=$(echo $OPENJDK_TAG | awk -F "-|[+]" '{print $3}')}
echo "##teamcity[setParameter name='env.JDK_UPDATE_NUMBER' value='${JDK_BUILD_NUMBER}']"
JBSDK_VERSION_WITH_DOTS=$(echo $JBSDK_VERSION | sed 's/_/\./g')

VENDOR_NAME="JetBrains s.r.o."
VENDOR_VERSION_STRING="JBR-${JBSDK_VERSION_WITH_DOTS}+${JDK_BUILD_NUMBER}-${build_number}"
[ -z "$bundle_type" ] || VENDOR_VERSION_STRING="${VENDOR_VERSION_STRING}-${bundle_type}"

do_reset_changes=0
do_reset_dcevm=0
HEAD_REVISION=0

OS_NAME=$(uname -s)
# Enable reproducible builds
TZ=UTC
export TZ
SOURCE_DATE_EPOCH="$(git log -1 --pretty=%ct)"
export SOURCE_DATE_EPOCH

case "$OS_NAME" in
    Linux)
        COPYRIGHT_YEAR="$(date --utc --date=@$SOURCE_DATE_EPOCH +%Y)"
        BUILD_TIME="$(date --utc --date=@$SOURCE_DATE_EPOCH +%F)"
        REPRODUCIBLE_TAR_OPTS="--mtime=@$SOURCE_DATE_EPOCH --owner=0 --group=0 --numeric-owner --pax-option=exthdr.name=%d/PaxHeaders/%f,delete=atime,delete=ctime"
        ;;
    Darwin)
        COPYRIGHT_YEAR="$(date -u -r $SOURCE_DATE_EPOCH +%Y)"
        BUILD_TIME="$(date -u -r $SOURCE_DATE_EPOCH +%F)"
        TOUCH_TIME="$(date -u -r $SOURCE_DATE_EPOCH +%Y%m%d%H%M.%S)"
        REPRODUCIBLE_TAR_OPTS="--uid 0 --gid 0 --numeric-owner"
        ;;
    *)
        # TODO: Windows
        ;;
esac

REPRODUCIBLE_BUILD_OPTS="--enable-reproducible-build
  --with-source-date=$SOURCE_DATE_EPOCH
  --with-hotspot-build-time=$BUILD_TIME
  --with-copyright-year=$COPYRIGHT_YEAR
  --disable-absolute-paths-in-output
  --with-build-user=builduser"

function do_exit() {
  exit_code=$1
  [ $do_reset_changes -eq 1 ] && git checkout HEAD modules.list src/java.desktop/share/classes/module-info.java
  if [ $do_reset_dcevm -eq 1 ]; then
    [ ! -z $HEAD_REVISION ] && git reset --hard $HEAD_REVISION
  fi
  exit "$exit_code"
}

function update_jsdk_mods() {
  __jsdk=$1
  __jcef_mods=$2
  __orig_jsdk_mods=$3
  __updated_jsdk_mods=$4
  
  # re-create java.desktop.jmod with updated module-info.class
  tmp=.java.desktop.$$.tmp
  mkdir "$tmp" || exit $?
  "$__jsdk"/bin/jmod extract --dir "$tmp" "$__orig_jsdk_mods"/java.desktop.jmod || exit $?
  "$__jsdk"/bin/javac \
      --patch-module java.desktop="$__orig_jsdk_mods"/java.desktop.jmod \
      --module-path "$__jcef_mods" -d "$tmp"/classes src/java.desktop/share/classes/module-info.java || exit $?
  "$__jsdk"/bin/jmod \
      create --class-path "$tmp"/classes --config "$tmp"/conf --header-files "$tmp"/include --legal-notice "$tmp"/legal --libs "$tmp"/lib \
      java.desktop.jmod || exit $?
  mv java.desktop.jmod "$__updated_jsdk_mods" || exit $?
  rm -rf "$tmp"
  
  # re-create java.base.jmod with updated hashes
  tmp=.java.base.$$.tmp
  mkdir "$tmp" || exit $?
  hash_modules=$("$JSDK"/bin/jmod describe "$__orig_jsdk_mods"/java.base.jmod | grep hashes | awk '{print $2}' | tr '\n' '|' | sed s/\|$//) || exit $?
  "$__jsdk"/bin/jmod extract --dir "$tmp" "$__orig_jsdk_mods"/java.base.jmod || exit $?
  rm "$__updated_jsdk_mods"/java.base.jmod || exit $? # temp exclude from path
  "$__jsdk"/bin/jmod \
      create --module-path "$__updated_jsdk_mods" --hash-modules "$hash_modules" \
      --class-path "$tmp"/classes --cmds "$tmp"/bin --config "$tmp"/conf --header-files "$tmp"/include --legal-notice "$tmp"/legal --libs "$tmp"/lib \
      java.base.jmod || exit $?
  mv java.base.jmod "$__updated_jsdk_mods" || exit $?
  rm -rf "$tmp"
}

function get_mods_list() {
  __mods=$1
  echo $(ls $__mods) | sed s/\.jmod/,/g | sed s/,$//g | sed s/' '//g
}

function copy_jmods() {
  __mods_list=$1
  __jmods_from=$2
  __jmods_to=$3

  mkdir -p $__jmods_to

  echo "${__mods_list}," | while read -d, mod; do cp $__jmods_from/$mod.jmod $__jmods_to/; done
}
