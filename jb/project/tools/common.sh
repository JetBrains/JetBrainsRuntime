VENDOR_NAME="JetBrains s.r.o."
VENDOR_VERSION_STRING="JBR-${JBSDK_VERSION_WITH_DOTS}.${JDK_BUILD_NUMBER}-${build_number}"
[ -z ${bundle_type} ] || VENDOR_VERSION_STRING="${VENDOR_VERSION_STRING}-${bundle_type}"

do_reset_changes=0
function do_exit() {
  exit_code=$1
  [ $do_reset_changes -eq 1 ] && git checkout HEAD modules.list src/java.desktop/share/classes/module-info.java
  exit $exit_code
}
