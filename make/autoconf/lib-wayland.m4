#
# Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

  if test "x$NEEDS_LIB_WAYLAND" = xfalse; then
    if (test "x${with_wayland}" != x && test "x${with_wayland}" != xno) || \
        (test "x${with_wayland_include}" != x && test "x${with_wayland_include}" != xno); then
      AC_MSG_WARN([[wayland not used, so --with-wayland[-*] is ignored]])
    fi
    WAYLAND_CFLAGS=
    WAYLAND_LIBS=
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
  fi

  AC_SUBST(WAYLAND_CFLAGS)
  AC_SUBST(WAYLAND_LIBS)
])
