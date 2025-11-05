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
  AC_ARG_WITH(wayland-protocols, [AS_HELP_STRING([--with-wayland-protocols],
      [specify the root directory for the wayland protocols xml files])])
  AC_ARG_WITH(gtk-shell1-protocol, [AS_HELP_STRING([--with-gtk-shell1-protocol],
      [specify the path to the gtk-shell1 Wayland protocol xml file])])
  AC_ARG_WITH(xkbcommon, [AS_HELP_STRING([--with-xkbcommon],
      [specify prefix directory for the xkbcommon package
      (expecting the headers under PATH/include)])])
  AC_ARG_WITH(xkbcommon-include, [AS_HELP_STRING([--with-xkbcommon-include],
      [specify directory for the xkbcommon include files])])
  AC_ARG_WITH(xkbcommon-lib, [AS_HELP_STRING([--with-xkbcommon-lib],
      [specify directory for the xkbcommon library files])])

  if test "x$NEEDS_LIB_WAYLAND" = xfalse; then
    if (test "x${with_wayland}" != x && test "x${with_wayland}" != xno) || \
        (test "x${with_wayland_include}" != x && test "x${with_wayland_include}" != xno); then
      AC_MSG_WARN([[wayland not used, so --with-wayland[-*] is ignored]])
    fi
    if (test "x${with_xkbcommon}" != x && test "x${with_xkbcommon}" != xno) || \
        (test "x${with_xkbcommon_include}" != x && test "x${with_xkbcommon_include}" != xno); then
      AC_MSG_WARN([[wayland not used, so --with-xkbcommon[-*] is ignored]])
    fi
    WAYLAND_CFLAGS=
    WAYLAND_LIBS=
  else
    WAYLAND_FOUND=no
    WAYLAND_INCLUDES=
    WAYLAND_DEFINES=
    if test "x${with_wayland}" = xno || test "x${with_wayland_include}" = xno; then
      AC_MSG_ERROR([It is not possible to disable the use of wayland. Remove the --without-wayland option.])
    fi
    if test "x${with_xkbcommon}" = xno || test "x${with_xkbcommon_include}" = xno; then
      AC_MSG_ERROR([It is not possible to disable the use of xkbcommon. Remove the --without-xkbcommon option.])
    fi

    if test "x${with_wayland}" != x; then
      AC_MSG_CHECKING([for wayland headers])
      if test -s "${with_wayland}/include/wayland-client.h" && test -s "${with_wayland}/include/wayland-cursor.h"; then
        WAYLAND_INCLUDES="-I${with_wayland}/include"
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
        WAYLAND_INCLUDES="-I${with_wayland_include}"
        WAYLAND_FOUND=yes
        AC_MSG_RESULT([$WAYLAND_FOUND])
      else
        AC_MSG_ERROR([Can't find 'wayland-client.h' and 'wayland-cursor.h' under ${with_wayland_include} given with the --with-wayland-include option.])
      fi
    fi
    UTIL_REQUIRE_PROGS(WAYLAND_SCANNER, wayland-scanner)
    if test "x${with_wayland_protocols}" != x; then
        WAYLAND_PROTOCOLS_ROOT=${with_wayland_protocols}
    else
        WAYLAND_PROTOCOLS_ROOT=/usr/share/wayland-protocols/
    fi
    AC_MSG_CHECKING([for wayland-protocols])
    if test -d "$WAYLAND_PROTOCOLS_ROOT"; then
      AC_MSG_RESULT([yes])
    else
      AC_MSG_ERROR([Can't find 'wayland-protocols' under $WAYLAND_PROTOCOLS_ROOT.])
    fi
    GTK_SHELL1_PROTOCOL_PATH=
    if test "x${with_gtk_shell1_protocol}" != x && test "x${with_gtk_shell1_protocol}" != xno; then
      AC_MSG_CHECKING([for the gtk-shell1 Wayland protocol])
      if test -s "${with_gtk_shell1_protocol}"; then
        WAYLAND_DEFINES="${WAYLAND_DEFINES} -DHAVE_GTK_SHELL1"
        GTK_SHELL1_PROTOCOL_PATH="${with_gtk_shell1_protocol}"
        AC_MSG_RESULT([yes])
      else
        AC_MSG_ERROR([Can't find gtk-shell1 protocol in ${with_gtk_shell1_protocol} given with the --with-gtk-shell1-protocol option.])
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
          WAYLAND_INCLUDES=
          WAYLAND_LIBS="-lwayland-client -lwayland-cursor"
          DEFAULT_WAYLAND=yes
      fi
    fi
    if test "x$WAYLAND_FOUND" = xno; then
      HELP_MSG_MISSING_DEPENDENCY([wayland])
      AC_MSG_ERROR([Could not find wayland! $HELP_MSG ])
    fi

    XKBCOMMON_FOUND=no
    XKBCOMMON_INCLUDES=
    XKBCOMMON_LIBS=-lxkbcommon
    if test "x${with_xkbcommon}" != x; then
      AC_MSG_CHECKING([for xkbcommon headers])
      if test -s "${with_xkbcommon}/include/xkbcommon/xkbcommon.h" &&
         test -s "${with_xkbcommon}/include/xkbcommon/xkbcommon-compose.h"; then
        XKBCOMMON_INCLUDES="-I${with_xkbcommon}/include"
        XKBCOMMON_LIBS="-L${with_xkbcommon}/lib ${XKBCOMMON_LIBS}"

        XKBCOMMON_FOUND=yes
        AC_MSG_RESULT([$XKBCOMMON_FOUND])
      else
        AC_MSG_ERROR([Can't find 'include/xkbcommon/xkbcommon.h' and 'include/xkbcommon/xkbcommon-compose.h' under ${with_xkbcommon} given with the --with-xkbcommon option.])
      fi
    fi
    if test "x${with_xkbcommon_include}" != x; then
      AC_MSG_CHECKING([for xkbcommon headers])
      if test -s "${with_xkbcommon_include}/xkbcommon/xkbcommon.h" &&
         test -s "${with_xkbcommon_include}/xkbcommon/xkbcommon-compose.h"; then
        XKBCOMMON_INCLUDES="-I${with_xkbcommon_include}"
        XKBCOMMON_FOUND=yes
        AC_MSG_RESULT([$XKBCOMMON_FOUND])
      else
        AC_MSG_ERROR([Can't find 'include/xkbcommon/xkbcommon.h' and 'include/xkbcommon/xkbcommon-compose.h' under ${with_xkbcommon_include} given with the --with-xkbcommon-include option.])
      fi
    fi
    if test "x${with_xkbcommon_lib}" != x; then
      XKBCOMMON_LIBS="-L${with_xkbcommon_lib} ${XKBCOMMON_LIBS}"
    fi

    if test "x${XKBCOMMON_FOUND}" != xyes; then
      AC_CHECK_HEADERS([xkbcommon/xkbcommon.h xkbcommon/xkbcommon-compose.h],
          [ XKBCOMMON_FOUND=yes ],
          [ XKBCOMMON_FOUND=no; break ]
      )
    fi

    if test "x$XKBCOMMON_FOUND" != xyes; then
      HELP_MSG_MISSING_DEPENDENCY([xkbcommon])
      AC_MSG_ERROR([Could not find xkbcommon! $HELP_MSG ])
    fi

    WAYLAND_LIBS="${WAYLAND_LIBS} ${XKBCOMMON_LIBS}"
    WAYLAND_CFLAGS="${WAYLAND_INCLUDES} ${XKBCOMMON_INCLUDES} ${WAYLAND_DEFINES}"
  fi
  AC_SUBST(WAYLAND_CFLAGS)
  AC_SUBST(WAYLAND_LIBS)
  AC_SUBST(WAYLAND_PROTOCOLS_ROOT)
  AC_SUBST(GTK_SHELL1_PROTOCOL_PATH)
])
