VENDOR_NAME="JetBrains s.r.o."
VENDOR_VERSION_STRING="JBR-${JBSDK_VERSION_WITH_DOTS}.${JDK_BUILD_NUMBER}-${build_number}"
[ -z "$bundle_type" ] || VENDOR_VERSION_STRING="${VENDOR_VERSION_STRING}-${bundle_type}"

do_reset_changes=0
do_reset_dcevm=0
HEAD_REVISION=0

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
