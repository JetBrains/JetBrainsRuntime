#
# Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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
# Setup wayland
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_WAYLAND],
[
  AC_ARG_WITH(wayland, [AS_HELP_STRING([--with-wayland],
      [specify prefix directory for the wayland package
      (expecting the headers under PATH/include)])])
  AC_ARG_WITH(wayland-include, [AS_HELP_STRING([--with-wayland-include],
      [specify directory for the wayland include files])])
  AC_ARG_WITH(wayland-lib, [AS_HELP_STRING([--with-wayland-lib],
      [specify directory for the wayland library files])])


  if test "x$NEEDS_LIB_WAYLAND" = xfalse; then
    if (test "x${with_wayland}" != x && test "x${with_wayland}" != xno) || \
        (test "x${with_wayland_include}" != x && test "x${with_wayland_include}" != xno); then
      AC_MSG_WARN([[wayland not used, so --with-wayland[-*] is ignored]])
    fi
    WAYLAND_CFLAGS=
    WAYLAND_LIBS=
    VULKAN_FLAGS=
    VULKAN_ENABLED=false
  else
    WAYLAND_FOUND=no

    if test "x${with_wayland}" = xno || test "x${with_wayland_include}" = xno; then
      AC_MSG_ERROR([It is not possible to disable the use of wayland. Remove the --without-wayland option.])
    fi

    if test "x${with_wayland}" != x; then
      AC_MSG_CHECKING([for wayland headers])
      if test -s "${with_wayland}/include/wayland-client.h" && test -s "${with_wayland}/include/wayland-cursor.h"; then
        WAYLAND_CFLAGS="-I${with_wayland}/include"
        WAYLAND_LIBS="-L${with_wayland}/lib -lwayland-client -lwayland-cursor"

        WAYLAND_FOUND=yes
        AC_MSG_RESULT([$WAYLAND_FOUND])
      else
        AC_MSG_ERROR([Can't find 'include/wayland-client.h' and 'include/wayland-cursor.h' under ${with_wayland} given with the --with-wayland option.])
      fi
    fi
    if test "x${with_wayland_include}" != x; then
      AC_MSG_CHECKING([for wayland headers])
      if test -s "${with_wayland_include}/wayland-client.h" && test -s "${with_wayland_include}/wayland-cursor.h"; then
        WAYLAND_CFLAGS="-I${with_wayland_include}"
        WAYLAND_FOUND=yes
        AC_MSG_RESULT([$WAYLAND_FOUND])
      else
        AC_MSG_ERROR([Can't find 'wayland-client.h' and 'wayland-cursor.h' under ${with_wayland_include} given with the --with-wayland-include option.])
      fi
    fi
    if test "x${with_wayland_lib}" != x; then
      WAYLAND_LIBS="-L${with_wayland_lib} -lwayland-client -lwayland-cursor"
    fi
 
    if test "x$WAYLAND_FOUND" = xno; then
      # Are the wayland headers installed in the default /usr/include location?
      AC_CHECK_HEADERS([wayland-client.h wayland-cursor.h],
          [ WAYLAND_FOUND=yes ],
          [ WAYLAND_FOUND=no; break ]
      )
      if test "x$WAYLAND_FOUND" = xyes; then
          WAYLAND_CFLAGS=
          WAYLAND_LIBS="-lwayland-client -lwayland-cursor"
          DEFAULT_WAYLAND=yes
      fi
    fi
    if test "x$WAYLAND_FOUND" = xno; then
      HELP_MSG_MISSING_DEPENDENCY([wayland])
      AC_MSG_ERROR([Could not find wayland! $HELP_MSG ])
    fi


    # Checking for vulkan sdk

    AC_ARG_WITH(vulkan, [AS_HELP_STRING([--with-vulkan],
      [specify whether we use vulkan])])

    AC_ARG_WITH(vulkan-include, [AS_HELP_STRING([--with-vulkan-include],
      [specify directory for the vulkan include files ({with-vulkan-include}/vulkan/vulkan.h)])])

    AC_ARG_WITH(vulkan-shader-compiler, [AS_HELP_STRING([--with-vulkan-shader-compiler],
      [specify which shader compiler to use: glslc/glslangValidator])])

    if test "x$SUPPORTS_LIB_VULKAN" = xfalse; then

      if (test "x${with_vulkan}" != x && test "x${with_vulkan}" != xno) || \
         (test "x${with_vulkan_include}" != x && test "x${with_vulkan_include}" != xno); then
        AC_MSG_WARN([[vulkan not used, so --with-vulkan-include is ignored]])
      fi
      VULKAN_FLAGS=
      VULKAN_ENABLED=false
    else
      # Do not build vulkan rendering pipeline by default
      if (test "x${with_vulkan}" = x && test "x${with_vulkan_include}" = x) || \
          test "x${with_vulkan}" = xno || test "x${with_vulkan_include}" = xno ; then
        VULKAN_FLAGS=
        VULKAN_ENABLED=false
      else
        VULKAN_FOUND=no

        if test "x${with_vulkan_include}" != x; then
          AC_MSG_CHECKING([for vulkan.h])
          if test -s "${with_vulkan_include}/vulkan/vulkan.h"; then
            VULKAN_FOUND=yes
            VULKAN_FLAGS="-DVK_USE_PLATFORM_WAYLAND_KHR -I${with_vulkan_include} -DVULKAN_ENABLED"
            AC_MSG_RESULT([yes])
          else
            AC_MSG_RESULT([no])
            AC_MSG_ERROR([Can't find 'vulkan/vulkan.h' under '${with_vulkan_include}'])
          fi
        fi

        if test "x$VULKAN_FOUND" = xno; then
          # Check vulkan sdk location
          AC_CHECK_HEADERS([$VULKAN_SDK/include/vulkan/vulkan.h],
            [ VULKAN_FOUND=yes
              VULKAN_FLAGS="-DVK_USE_PLATFORM_WAYLAND_KHR -I${VULKAN_SDK}/include -DVULKAN_ENABLED"
            ],
            [ VULKAN_FOUND=no; break ]
          )
        fi

        if test "x$VULKAN_FOUND" = xno; then
          # Check default /usr/include location
          AC_CHECK_HEADERS([vulkan/vulkan.h],
            [ VULKAN_FOUND=yes
              VULKAN_FLAGS="-DVK_USE_PLATFORM_WAYLAND_KHR -DVULKAN_ENABLED"
            ],
            [ VULKAN_FOUND=no; break ]
          )
        fi

        if test "x$VULKAN_FOUND" = xno; then
          HELP_MSG_MISSING_DEPENDENCY([vulkan])
          AC_MSG_ERROR([Could not find vulkan! $HELP_MSG ])
        else
          # Find shader compiler - glslc or glslangValidator
          if (test "x${with_vulkan_shader_compiler}" = x || test "x${with_vulkan_shader_compiler}" = xglslc); then
            UTIL_LOOKUP_PROGS(GLSLC, glslc)
            SHADER_COMPILER="$GLSLC"
            VULKAN_SHADER_COMPILER="glslc --target-env=vulkan1.2 -mfmt=num -o"
          fi

          if (test "x${with_vulkan_shader_compiler}" = x || test "x${with_vulkan_shader_compiler}" = xglslangValidator) && \
              test "x$SHADER_COMPILER" = x; then
            UTIL_LOOKUP_PROGS(GLSLANG, glslangValidator)
            SHADER_COMPILER="$GLSLANG"
            VULKAN_SHADER_COMPILER="glslangValidator --target-env vulkan1.2 -x -o"
          fi

          if test "x$SHADER_COMPILER" != x; then
            VULKAN_ENABLED=true
          else
            AC_MSG_ERROR([Can't find shader compiler])
          fi
        fi
      fi
    fi
  fi
  AC_SUBST(VULKAN_FLAGS)
  AC_SUBST(VULKAN_SHADER_COMPILER)
  AC_SUBST(VULKAN_ENABLED)
  AC_SUBST(WAYLAND_CFLAGS)
  AC_SUBST(WAYLAND_LIBS)
])
