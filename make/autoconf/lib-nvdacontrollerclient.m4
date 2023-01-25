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
# Setup nvdacontrollerclient (The library for communication with
# NVDA - a screen reader for Microsoft Windows)
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_NVDACONTROLLERCLIENT], [
  # To enable NVDA, user specifies neither --with-nvdacontrollerclient or
  # a pair (--with-nvdacontrollerclient-include, --with-nvdacontrollerclient-lib)
  AC_ARG_WITH(nvdacontrollerclient, [AS_HELP_STRING([--with-nvdacontrollerclient],
      [specify prefix directory for the NVDA Controller Client library package
       (expecting headers and libs under PATH/<target-arch>/)])])
  AC_ARG_WITH(nvdacontrollerclient-include, [AS_HELP_STRING([--with-nvdacontrollerclient-include],
      [specify directory for the NVDA Controller Client include files])])
  AC_ARG_WITH(nvdacontrollerclient-lib, [AS_HELP_STRING([--with-nvdacontrollerclient-lib],
      [specify directory for the NVDA Controller Client library])])

  NVDACONTROLLERCLIENT_FOUND=no
  NVDACONTROLLERCLIENT_LIB=
  NVDACONTROLLERCLIENT_DLL=
  NVDACONTROLLERCLIENT_CFLAGS=

  if test "x${NEEDS_LIB_NVDACONTROLLERCLIENT}" = "xtrue" ; then
    if (test "x${with_nvdacontrollerclient_include}"  = "x" && test "x${with_nvdacontrollerclient_lib}" != "x") || \
       (test "x${with_nvdacontrollerclient_include}" != "x" && test "x${with_nvdacontrollerclient_lib}"  = "x") ; then
      AC_MSG_ERROR([Must specify both or neither of --with-nvdacontrollerclient-include and --with-nvdacontrollerclient-lib])
    elif (test "x${with_nvdacontrollerclient}" != "x" && test "x${with_nvdacontrollerclient_include}" != "x") ; then
      AC_MSG_ERROR([Must specify either --with-nvdacontrollerclient or a pair (--with-nvdacontrollerclient-include, --with-nvdacontrollerclient-lib)])
    fi

    if (test "x${with_nvdacontrollerclient}" != "x") || \
       (test "x${with_nvdacontrollerclient_include}" != "x" && test "x${with_nvdacontrollerclient_lib}" != "x") ; then

      AC_MSG_CHECKING([for nvdacontrollerclient])

      if test "x${OPENJDK_TARGET_OS}" != "xwindows" ; then
        AC_MSG_ERROR([--with-nvdacontrollerclient[-*] flags are applicable only to Windows builds])
      fi

      if test "x${OPENJDK_TARGET_CPU_ARCH}" = "xaarch64" ; then
        NVDACONTROLLERCLIENT_BIN_BASENAME="nvdaControllerClient32"
        NVDACONTROLLERCLIENT_ARCHDIR="arm64"
      elif test "x${OPENJDK_TARGET_CPU_ARCH}" = "xx86" && test "x${OPENJDK_TARGET_CPU_BITS}" = "x64" ; then
        NVDACONTROLLERCLIENT_BIN_BASENAME="nvdaControllerClient64"
        NVDACONTROLLERCLIENT_ARCHDIR="x64"
      elif test "x${OPENJDK_TARGET_CPU_ARCH}" = "xx86" && test "x${OPENJDK_TARGET_CPU_BITS}" = "x32" ; then
        NVDACONTROLLERCLIENT_BIN_BASENAME="nvdaControllerClient32"
        NVDACONTROLLERCLIENT_ARCHDIR="x86"
      else
        AC_MSG_ERROR([The nvdacontrollerclient library exists only for x86_32, x86_64, AArch64 architectures])
      fi

      if test "x${with_nvdacontrollerclient}" != "x" ; then
        # NVDACONTROLLERCLIENT_ARCHDIR is used only here
        NVDACONTROLLERCLIENT_INC_PATH="${with_nvdacontrollerclient}/${NVDACONTROLLERCLIENT_ARCHDIR}"
        NVDACONTROLLERCLIENT_BIN_PATH="${with_nvdacontrollerclient}/${NVDACONTROLLERCLIENT_ARCHDIR}"
      else
        NVDACONTROLLERCLIENT_INC_PATH="${with_nvdacontrollerclient_include}"
        NVDACONTROLLERCLIENT_BIN_PATH="${with_nvdacontrollerclient_lib}"
      fi

      POTENTIAL_NVDACONTROLLERCLIENT_DLL="${NVDACONTROLLERCLIENT_BIN_PATH}/${NVDACONTROLLERCLIENT_BIN_BASENAME}.dll"
      POTENTIAL_NVDACONTROLLERCLIENT_LIB="${NVDACONTROLLERCLIENT_BIN_PATH}/${NVDACONTROLLERCLIENT_BIN_BASENAME}.lib"
      POTENTIAL_NVDACONTROLLERCLIENT_EXP="${NVDACONTROLLERCLIENT_BIN_PATH}/${NVDACONTROLLERCLIENT_BIN_BASENAME}.exp"

      if ! test -s "${POTENTIAL_NVDACONTROLLERCLIENT_DLL}" || \
         ! test -s "${POTENTIAL_NVDACONTROLLERCLIENT_LIB}" || \
         ! test -s "${POTENTIAL_NVDACONTROLLERCLIENT_EXP}" ; then
          AC_MSG_ERROR([Could not find ${NVDACONTROLLERCLIENT_BIN_BASENAME}.dll and/or ${NVDACONTROLLERCLIENT_BIN_BASENAME}.lib and/or ${NVDACONTROLLERCLIENT_BIN_BASENAME}.exp inside ${NVDACONTROLLERCLIENT_BIN_PATH}])
      fi
      if ! test -s "${NVDACONTROLLERCLIENT_INC_PATH}/nvdaController.h" ; then
        AC_MSG_ERROR([Could not find the header file nvdaController.h inside ${NVDACONTROLLERCLIENT_INC_PATH}])
      fi

      NVDACONTROLLERCLIENT_CFLAGS="-I${NVDACONTROLLERCLIENT_INC_PATH}"
      NVDACONTROLLERCLIENT_DLL="${POTENTIAL_NVDACONTROLLERCLIENT_DLL}"
      NVDACONTROLLERCLIENT_LIB="${POTENTIAL_NVDACONTROLLERCLIENT_LIB}"
      NVDACONTROLLERCLIENT_FOUND=yes

      AC_MSG_RESULT([includes at ${NVDACONTROLLERCLIENT_INC_PATH} ; binaries at ${NVDACONTROLLERCLIENT_BIN_PATH}])
    fi
  elif test "x${with_nvdacontrollerclient}" != "x" || \
       test "x${with_nvdacontrollerclient_include}" != "x" || test "x${with_nvdacontrollerclient_lib}" != "x" ; then
    AC_MSG_WARN([[nvdacontrollerclient is not used, so --with-nvdacontrollerclient[-*] is ignored]])
  fi

  if test "x${NVDACONTROLLERCLIENT_FOUND}" = "xyes" ; then
    A11Y_NVDA_ANNOUNCING_ENABLED=true
  else
    A11Y_NVDA_ANNOUNCING_ENABLED=false
  fi

  AC_SUBST(A11Y_NVDA_ANNOUNCING_ENABLED)
  AC_SUBST(NVDACONTROLLERCLIENT_CFLAGS)
  AC_SUBST(NVDACONTROLLERCLIENT_DLL)
  AC_SUBST(NVDACONTROLLERCLIENT_LIB)
])