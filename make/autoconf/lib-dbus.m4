#
# Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2024, JetBrains s.r.o.. All rights reserved.
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
# Check if a potential dbus library match is correct and usable
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_DBUS],
[
  AC_ARG_WITH(dbus-includes, [AS_HELP_STRING([--with-dbus-includes],
      [specify include directories for the dbus files as list separated by space])])

  if test "x$NEEDS_LIB_DBUS" = xfalse; then
    DBUS_CFLAGS=
    DBUS_FOUND=false
  else
    if test "x${with_dbus_includes}" != x; then
      DBUS_FOUND=true
      DBUS_CFLAGS=""
      for include in $with_dbus_includes; do
        DBUS_CFLAGS="${DBUS_CFLAGS}-I${include} "
      done
    else
      PKG_CHECK_MODULES(DBUS, dbus-1, [DBUS_FOUND=true], [
        DBUS_FOUND=false
        AC_MSG_NOTICE([Can't find dbus-1 library. This library is needed to use some features. You can install dbus-1 library or specify include directories manually by giving --with-dbus-includes option.])
      ])
    fi
  fi

  AC_SUBST(DBUS_CFLAGS)
  AC_SUBST(DBUS_FOUND)
])