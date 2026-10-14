#
# Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

################################################################################
# Setup Skia (experimental Skia-based Java2D rendering backend)
#
# Skia is consumed as a prebuilt: --with-skia points at an unpacked archive from
# the publisher pinned in jb/project/tools/common/skia_prebuilt.conf, as produced
# by jb/project/tools/common/scripts/fetch_skia.sh. The layout is the one of the
# JetBrains/skia releases:
#   <dir>/include/...                        headers
#   <dir>/out/<BuildType>-<os>-<arch>/*.a    static libraries
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_SKIA],
[
  AC_ARG_WITH(skia, [AS_HELP_STRING([--with-skia],
      [specify the directory of an unpacked prebuilt Skia to enable the experimental
       Skia rendering backend (see jb/project/tools/common/scripts/fetch_skia.sh)])])

  SKIA_ENABLED=false
  SKIA_CFLAGS=
  SKIA_LIBS=
  SKIA_INCLUDE_DIR=
  SKIA_LIB_DIR=

  if test "x${with_skia}" != x && test "x${with_skia}" != xno; then
    if test "x${with_skia}" = xyes; then
      AC_MSG_ERROR([--with-skia needs a directory argument; run jb/project/tools/common/scripts/fetch_skia.sh to obtain one])
    fi

    SKIA_INCLUDE_DIR="${with_skia}"
    UTIL_FIXUP_PATH(SKIA_INCLUDE_DIR)

    AC_MSG_CHECKING([for ${SKIA_INCLUDE_DIR}/include/core/SkCanvas.h])
    if test -s "${SKIA_INCLUDE_DIR}/include/core/SkCanvas.h"; then
      AC_MSG_RESULT([yes])
    else
      AC_MSG_RESULT([no])
      AC_MSG_ERROR([Can't find Skia headers under '${with_skia}'])
    fi

    AC_MSG_CHECKING([for Skia static libraries])
    SKIA_LIB_DIR=`ls -d "${SKIA_INCLUDE_DIR}"/out/*/ 2> /dev/null | head -n 1`
    if test "x${SKIA_LIB_DIR}" != x && test -s "${SKIA_LIB_DIR}/libskia.a"; then
      SKIA_LIB_DIR=`echo "${SKIA_LIB_DIR}" | $SED 's|/$||'`
      UTIL_FIXUP_PATH(SKIA_LIB_DIR)
      AC_MSG_RESULT([${SKIA_LIB_DIR}])
    else
      AC_MSG_RESULT([no])
      AC_MSG_ERROR([Can't find out/<config>/libskia.a under '${with_skia}'])
    fi

    # fetch_skia.sh leaves the SHA-256 of the verified archive next to the
    # unpacked tree; compare it with the pin so a stale or foreign unpack is
    # noticed at configure time (a warning: hand-built Skias are legitimate).
    AC_MSG_CHECKING([whether the Skia prebuilt matches skia_prebuilt.conf])
    skia_marker="${SKIA_INCLUDE_DIR}/.fetch_skia.complete"
    skia_conf="${TOPDIR}/jb/project/tools/common/skia_prebuilt.conf"
    skia_pin_os=`echo "$OPENJDK_TARGET_OS" | $SED 's/macosx/macos/'`
    skia_pin_cpu=`echo "$OPENJDK_TARGET_CPU" | $SED -e 's/aarch64/arm64/' -e 's/x86_64/x64/'`
    skia_pin=`$GREP "^SKIA_SHA256_${skia_pin_os}_${skia_pin_cpu}=" "${skia_conf}" 2> /dev/null | $CUT -d= -f2`
    if test ! -f "${skia_marker}"; then
      AC_MSG_RESULT([unknown (no .fetch_skia.complete marker, not produced by fetch_skia.sh)])
    elif test "x${skia_pin}" = x; then
      AC_MSG_RESULT([unknown (no pin for ${skia_pin_os}/${skia_pin_cpu})])
    elif test "x`$CAT "${skia_marker}"`" = "x${skia_pin}"; then
      AC_MSG_RESULT([yes])
    else
      AC_MSG_RESULT([no])
      AC_MSG_WARN([The Skia prebuilt under '${with_skia}' is not the one pinned in skia_prebuilt.conf (${skia_pin})])
    fi
    # Graphite only; the GPU API matches the Java2D backend of each platform.
    # SK_RELEASE: the prebuilt is a Release build and SkTypes.h would otherwise
    # pick SK_DEBUG (different class layouts) for fastdebug/slowdebug JDK builds.
    # Skia's headers need C++17 (JDK release lines before 28 compile C++ as C++14).
    SKIA_CFLAGS="-I${SKIA_INCLUDE_DIR} -DSK_GRAPHITE -DSK_RELEASE -std=c++17"
    if test "x$OPENJDK_TARGET_OS" = xmacosx; then
      SKIA_CFLAGS="$SKIA_CFLAGS -DSK_METAL"
    elif test "x$OPENJDK_TARGET_OS" = xlinux; then
      SKIA_CFLAGS="$SKIA_CFLAGS -DSK_VULKAN"
    elif test "x$OPENJDK_TARGET_OS" = xwindows; then
      SKIA_CFLAGS="$SKIA_CFLAGS -DSK_DAWN"
    fi

    # Link order: GPU extension libs first, then core, then bundled dependencies.
    for lib in skia_graphite_ext skia skcms zlib png jpeg jpeg12 jpeg16 webp webp_sse41 \
        wuffs piex dng_sdk expat; do
      if test -s "${SKIA_LIB_DIR}/lib${lib}.a"; then
        SKIA_LIBS="$SKIA_LIBS ${SKIA_LIB_DIR}/lib${lib}.a"
      fi
    done

    SKIA_ENABLED=true
  fi

  AC_SUBST(SKIA_ENABLED)
  AC_SUBST(SKIA_CFLAGS)
  AC_SUBST(SKIA_LIBS)
  AC_SUBST(SKIA_INCLUDE_DIR)
  AC_SUBST(SKIA_LIB_DIR)
])
