#
# Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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
# Setup speechd
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_SPEECHD],
[
  AC_ARG_WITH(speechd, [AS_HELP_STRING([--with-speechd],
      [specify prefix directory for the libspeechd package
      (expecting the headers under PATH/include); required for AccessibleAnnouncer to work])])
  AC_ARG_WITH(speechd-include, [AS_HELP_STRING([--with-speechd-include],
      [specify directory for the speechd include files])])

  if test "x$NEEDS_LIB_SPEECHD" = xfalse || test "x${with_speechd}" = xno || \
      test "x${with_speechd_include}" = xno; then
    if (test "x${with_speechd}" != x && test "x${with_speechd}" != xno) || \
        (test "x${with_speechd_include}" != x && test "x${with_speechd_include}" != xno); then
      AC_MSG_WARN([[speechd not used, so --with-speechd[-*] is ignored]])
    fi
    A11Y_SPEECHD_ANNOUNCING_ENABLED=false
    SPEECHD_CFLAGS=
    SPEECHD_LIBS=
  else
    SPEECHD_FOUND=no

    if test "x${with_speechd}" != x && test "x${with_speechd}" != xyes; then
      AC_MSG_CHECKING([for speechd header and library])
      if test -s "${with_speechd}/include/libspeechd.h"; then
        SPEECHD_CFLAGS="-I${with_speechd}/include"
        SPEECHD_LIBS="-L${with_speechd}/lib -lspeechd"
        SPEECHD_FOUND=yes
        AC_MSG_RESULT([$SPEECHD_FOUND])
      else
        AC_MSG_ERROR([Can't find 'include/libspeechd.h' under ${with_speechd} given with the --with-speechd option.])
      fi
    fi
    if test "x${with_speechd_include}" != x; then
      AC_MSG_CHECKING([for speechd headers])
      if test -s "${with_speechd_include}/libspeechd.h"; then
        SPEECHD_CFLAGS="-I${with_speechd_include}"
        SPEECHD_FOUND=yes
        AC_MSG_RESULT([$SPEECHD_FOUND])
      else
        AC_MSG_ERROR([Can't find 'include/libspeechd.h' under ${with_speechd} given with the --with-speechd-include option.])
      fi
    fi
    if test "x$SPEECHD_FOUND" = xno; then
      # Are the libspeechd headers installed in the default /usr/include location?
      AC_CHECK_HEADERS([libspeechd.h],
          [ SPEECHD_FOUND=yes ],
          [ SPEECHD_FOUND=no; break ]
      )
      if test "x$SPEECHD_FOUND" = xyes; then
          SPEECHD_CFLAGS=
          SPEECHD_LIBS="-lspeechd"
      fi
    fi
    if test "x$SPEECHD_FOUND" = xno; then
      A11Y_SPEECHD_ANNOUNCING_ENABLED=false
    else
      A11Y_SPEECHD_ANNOUNCING_ENABLED=true
    fi
  fi

  AC_SUBST(A11Y_SPEECHD_ANNOUNCING_ENABLED)
  AC_SUBST(SPEECHD_CFLAGS)
  AC_SUBST(SPEECHD_LIBS)
])
