#
# Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2025, JetBrains s.r.o.. All rights reserved.
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
# Setup vulkan
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_VULKAN],
[
  AC_ARG_WITH(vulkan, [AS_HELP_STRING([--with-vulkan],
    [specify whether we use vulkan])])
  AC_ARG_WITH(vulkan-include, [AS_HELP_STRING([--with-vulkan-include],
    [specify directory for the vulkan include files ({with-vulkan-include}/vulkan/vulkan.h)])])
  AC_ARG_WITH(vulkan-shader-compiler, [AS_HELP_STRING([--with-vulkan-shader-compiler],
    [specify which shader compiler to use: glslc/glslangValidator])])

  VULKAN_ENABLED=false
  VULKAN_FLAGS=

  # Find Vulkan SDK
  if test "x$NEEDS_LIB_VULKAN" = xtrue || test "x${with_vulkan}" = xyes || test "x${with_vulkan_include}" != x ; then

    # Check custom directory
    if test "x${with_vulkan_include}" != x; then
      AC_MSG_CHECKING([for ${with_vulkan_include}/vulkan/vulkan.h])
      if test -s "${with_vulkan_include}/vulkan/vulkan.h"; then
        VULKAN_ENABLED=true
        VULKAN_FLAGS="-I${with_vulkan_include}"
        AC_MSG_RESULT([yes])
      else
        AC_MSG_RESULT([no])
        AC_MSG_ERROR([Can't find 'vulkan/vulkan.h' under '${with_vulkan_include}'])
      fi
    fi

    # Check $VULKAN_SDK
    if test "x$VULKAN_ENABLED" = xfalse && test "x${VULKAN_SDK}" != x; then
      AC_MSG_CHECKING([for ${VULKAN_SDK}/include/vulkan/vulkan.h])
      if test -s "${VULKAN_SDK}/include/vulkan/vulkan.h"; then
        VULKAN_ENABLED=true
        VULKAN_FLAGS="-I${VULKAN_SDK}/include"
        AC_MSG_RESULT([yes])
      else
        AC_MSG_RESULT([no])
      fi
    fi

    # Check default /usr/include location
    if test "x$VULKAN_ENABLED" = xfalse; then
      AC_CHECK_HEADERS([vulkan/vulkan.h],
        [ VULKAN_ENABLED=true ], [ break ]
      )
    fi

    if test "x$VULKAN_ENABLED" = xfalse; then
      # Vulkan SDK not found
      HELP_MSG_MISSING_DEPENDENCY([vulkan])
      AC_MSG_ERROR([Could not find vulkan! $HELP_MSG ])
    fi
  fi

  # Find shader compiler - glslc or glslangValidator
  if test "x$VULKAN_ENABLED" = xtrue; then
    SHADER_COMPILER=

    # Check glslc
    if (test "x${with_vulkan_shader_compiler}" = x || test "x${with_vulkan_shader_compiler}" = xglslc); then
      UTIL_LOOKUP_PROGS(GLSLC, glslc)
      SHADER_COMPILER="$GLSLC"
      VULKAN_SHADER_COMPILER="glslc --target-env=vulkan1.2 -mfmt=num"
    fi

    # Check glslangValidator
    if (test "x${with_vulkan_shader_compiler}" = x || test "x${with_vulkan_shader_compiler}" = xglslangValidator) && \
        test "x$SHADER_COMPILER" = x; then
      UTIL_LOOKUP_PROGS(GLSLANG, glslangValidator)
      SHADER_COMPILER="$GLSLANG"
      # Newer glslangValidator could use -P\"\#extension GL_GOOGLE_include_directive: require\"
      VULKAN_SHADER_COMPILER="glslangValidator --target-env vulkan1.2 -x"
    fi

    if test "x$SHADER_COMPILER" = x; then
      # Compiler not found
      VULKAN_ENABLED=false
      VULKAN_FLAGS=
      AC_MSG_ERROR([Can't find vulkan shader compiler])
    fi
  fi

  AC_SUBST(VULKAN_ENABLED)
  AC_SUBST(VULKAN_FLAGS)
  AC_SUBST(VULKAN_SHADER_COMPILER)
])
