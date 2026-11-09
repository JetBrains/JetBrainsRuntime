#
# Copyright 2026 JetBrains s.r.o.
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
# Setup Skia (the Skia rendering backend, -Dsun.java2d.skia=true)
#
# Skia is consumed as a prebuilt: --with-skia points at an unpacked archive from
# the publisher pinned in jb/project/tools/common/skia_prebuilt.conf, as produced
# by jb/project/tools/common/scripts/fetch_skia.sh. The layout is the one of the
# JetBrains/skia releases:
#   <dir>/include/...                        headers
#   <dir>/out/<BuildType>-<os>-<arch>/*.lib  static libraries
# The backend has a platform half for Windows x64 only; elsewhere --with-skia
# stops configure.
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_SKIA],
[
  AC_ARG_WITH(skia, [AS_HELP_STRING([--with-skia],
      [specify the directory of an unpacked prebuilt Skia to build the Skia rendering
       backend, Windows x64 only (see jb/project/tools/common/scripts/fetch_skia.sh)])])

  SKIA_ENABLED=false
  SKIA_CFLAGS=
  SKIA_CRT_FLAGS=
  SKIA_LIBS=
  SKIA_INCLUDE_DIR=
  SKIA_LIB_DIR=

  if test "x${with_skia}" != x && test "x${with_skia}" != xno; then
    if test "x${with_skia}" = xyes; then
      AC_MSG_ERROR([--with-skia needs a directory argument; run jb/project/tools/common/scripts/fetch_skia.sh to obtain one])
    fi
    if test "x$OPENJDK_TARGET_OS" != xwindows || test "x$OPENJDK_TARGET_CPU" != xx86_64; then
      AC_MSG_ERROR([--with-skia: the Skia backend has no platform half for $OPENJDK_TARGET_OS-$OPENJDK_TARGET_CPU (Windows x64 only)])
    fi

    # The pin the prebuilt is checked against (fetch_skia.sh reads the same file)
    skia_pin_file="${TOPDIR}/jb/project/tools/common/skia_prebuilt.conf"
    skia_build_type=`$GREP "^SKIA_BUILD_TYPE=" "${skia_pin_file}" 2> /dev/null | $CUT -d= -f2`
    skia_pinned_sha256=`$GREP "^SKIA_SHA256_windows_x64=" "${skia_pin_file}" 2> /dev/null | $CUT -d= -f2`
    skia_pinned_tag=`$GREP "^SKIA_TAG=" "${skia_pin_file}" 2> /dev/null | $CUT -d= -f2`

    AC_MSG_CHECKING([for ${with_skia}/include/core/SkCanvas.h])
    if test -s "${with_skia}/include/core/SkCanvas.h"; then
      AC_MSG_RESULT([yes])
    else
      AC_MSG_RESULT([no])
      AC_MSG_ERROR([--with-skia: no Skia headers under '${with_skia}'])
    fi
    SKIA_INCLUDE_DIR="${with_skia}"
    UTIL_FIXUP_PATH(SKIA_INCLUDE_DIR)

    AC_MSG_CHECKING([for Skia static libraries])
    SKIA_LIB_DIR="${SKIA_INCLUDE_DIR}/out/${skia_build_type:-Release}-windows-x64"
    if test -s "${SKIA_LIB_DIR}/skia.lib"; then
      UTIL_FIXUP_PATH(SKIA_LIB_DIR)
      AC_MSG_RESULT([${SKIA_LIB_DIR}])
    else
      AC_MSG_RESULT([no])
      AC_MSG_ERROR([--with-skia: no out/${skia_build_type:-Release}-windows-x64/skia.lib under '${with_skia}'])
    fi

    # fetch_skia.sh leaves the SHA-256 of the verified archive next to the
    # unpacked tree; compare it with the pin so a stale or foreign unpack is
    # noticed at configure time (a warning: hand-built Skias are legitimate).
    AC_MSG_CHECKING([whether the Skia prebuilt matches skia_prebuilt.conf])
    skia_marker="${SKIA_INCLUDE_DIR}/.fetch_skia.complete"
    skia_is_pinned=false
    if test ! -f "${skia_marker}"; then
      AC_MSG_RESULT([unknown (no .fetch_skia.complete marker, not produced by fetch_skia.sh)])
    elif test "x`$CAT "${skia_marker}"`" = "x${skia_pinned_sha256}"; then
      AC_MSG_RESULT([yes])
      skia_is_pinned=true
    else
      AC_MSG_RESULT([no])
      AC_MSG_WARN([--with-skia: the prebuilt under '${with_skia}' is not the one pinned in skia_prebuilt.conf (${skia_pinned_sha256})])
    fi

    # The backend's GPU list is generated for one Skia release from Chromium's lists at the matching
    # revision (update_gpu_lists.sh); a bump of SKIA_TAG without regenerating it is noticed here.
    AC_MSG_CHECKING([whether the Skia GPU list was generated for the pinned Skia])
    skia_gpu_list_file="${TOPDIR}/src/java.desktop/share/classes/sun/java2d/skia/SKGPUListData.java"
    skia_gpu_list_tag=`$GREP "String SKIA_TAG = " "${skia_gpu_list_file}" 2> /dev/null | $SED 's/.*"\(.*\)".*/\1/'`
    if test "x${skia_gpu_list_tag}" = "x${skia_pinned_tag}"; then
      AC_MSG_RESULT([yes])
    else
      AC_MSG_RESULT([no (${skia_gpu_list_tag})])
      AC_MSG_WARN([--with-skia: SKGPUListData.java was generated for Skia ${skia_gpu_list_tag}, the pin is ${skia_pinned_tag}; run jb/project/tools/common/scripts/update_gpu_lists.sh])
    fi

    # The release the backend reports (SKIA_RELEASE: its pipeline cache is keyed by it): the pin's tag when the
    # unpacked tree is the pinned one, "unpinned" otherwise (a hand-built Skia must not share a cache key with
    # the release); reduced to the characters a tag has, so it stays one preprocessor token.
    skia_release=unpinned
    if test "x${skia_is_pinned}" = xtrue; then
      skia_release=`$ECHO "${skia_pinned_tag}" | $TR -cd 'A-Za-z0-9._-'`
    fi

    # Graphite on Dawn (Direct3D 11, Direct3D 12) and on native Vulkan.
    # SK_RELEASE: the prebuilt is a Release build and SkTypes.h would otherwise
    # pick SK_DEBUG (different class layouts) for fastdebug/slowdebug JDK builds.
    # The WebGPU headers come in two halves: the hand-written ones in the Dawn
    # checkout and the generated ones next to the libraries; Vulkan's are under
    # include/third_party/vulkan (the loader is loaded at run time). Skia's
    # headers use std::min/max (NOMINMAX) and pull in <ostream>, which MSVC
    # rejects without exception unwinding (-EHsc); Dawn's webgpu_cpp.h needs
    # C++20 (std::span). The release is built against the static CRT (-MT) and
    # the linker refuses to mix it with -MD objects, so every object of
    # libawt_skia is compiled -MT (SKIA_CRT_FLAGS for its C files). Safe because
    # its boundary with the other JDK libraries is JNI and malloc'd blocks only,
    # and both CRTs allocate from the process heap since the UCRT. -pathmap: the
    # prebuilt's paths (its headers' __FILE__, debug info) read the same on every
    # machine, as the JDK maps its workspace, for a prebuilt cached elsewhere.
    SKIA_CRT_FLAGS="-MT"
    SKIA_CFLAGS="-I${SKIA_INCLUDE_DIR} -DSK_GRAPHITE -DSK_RELEASE -DSK_DAWN -DSK_VULKAN -DSKIA_RELEASE=${skia_release:-unknown} \
        -DNOMINMAX -EHsc ${SKIA_CRT_FLAGS} -std:c++20 -pathmap:${SKIA_INCLUDE_DIR}=skia \
        -I${SKIA_INCLUDE_DIR}/third_party/externals/dawn/include \
        -I${SKIA_LIB_DIR}/gen/third_party/dawn/include \
        -I${SKIA_INCLUDE_DIR}/include/third_party/vulkan"

    # Graphite's Dawn extension, Graphite and core, then Dawn with the parts of
    # Chromium's allocator it is built on. skia.lib carries skcms, the Vulkan
    # Memory Allocator and the image codecs core refers to (libjpeg-turbo,
    # libpng, libwebp, wuffs, zlib) itself; the release's separate libraries of
    # them, its text modules (the backend draws Java's glyph images) and D3D12MA
    # contribute nothing. allocator_shim is left out: it replaces malloc/free
    # process-wide (PartitionAlloc's shim) and collides with the CRT's. The
    # third-party code linked has its notices in src/java.desktop/windows/legal.
    for skia_lib in skia_graphite_dawn_ext skia_graphite_ext skia dawn_combined \
        allocator_core allocator_base raw_ptr; do
      if test ! -s "${SKIA_LIB_DIR}/${skia_lib}.lib"; then
        AC_MSG_ERROR([--with-skia: the release under '${with_skia}' has no ${skia_lib}.lib])
      fi
      SKIA_LIBS="$SKIA_LIBS ${SKIA_LIB_DIR}/${skia_lib}.lib"
    done

    SKIA_ENABLED=true
  fi

  AC_SUBST(SKIA_ENABLED)
  AC_SUBST(SKIA_CFLAGS)
  AC_SUBST(SKIA_CRT_FLAGS)
  AC_SUBST(SKIA_LIBS)
])
