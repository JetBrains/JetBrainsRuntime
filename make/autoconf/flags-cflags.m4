#
# Copyright (c) 2011, 2019, Oracle and/or its affiliates. All rights reserved.
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
#
# Setup flags for C/C++ compiler
#

###############################################################################
#
# How to compile shared libraries.
#
AC_DEFUN([FLAGS_SETUP_SHARED_LIBS],
[
  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    C_FLAG_REORDER=''

    # Default works for linux, might work on other platforms as well.
    SHARED_LIBRARY_FLAGS='-shared'
    SET_EXECUTABLE_ORIGIN='-Wl,-rpath,\$$ORIGIN[$]1'
    SET_SHARED_LIBRARY_ORIGIN="-Wl,-z,origin $SET_EXECUTABLE_ORIGIN"
    SET_SHARED_LIBRARY_NAME='-Wl,-soname=[$]1'
    SET_SHARED_LIBRARY_MAPFILE='-Wl,-version-script=[$]1'

  elif test "x$TOOLCHAIN_TYPE" = xclang; then
    C_FLAG_REORDER=''

    if test "x$OPENJDK_TARGET_OS" = xmacosx; then
      # Linking is different on MacOSX
      SHARED_LIBRARY_FLAGS="-dynamiclib -compatibility_version 1.0.0 -current_version 1.0.0"
      SET_EXECUTABLE_ORIGIN='-Wl,-rpath,@loader_path$(or [$]1,/.)'
      SET_SHARED_LIBRARY_ORIGIN="$SET_EXECUTABLE_ORIGIN"
      SET_SHARED_LIBRARY_NAME='-Wl,-install_name,@rpath/[$]1'
      SET_SHARED_LIBRARY_MAPFILE='-Wl,-exported_symbols_list,[$]1'

    else
      # Default works for linux, might work on other platforms as well.
      SHARED_LIBRARY_FLAGS='-shared'
      SET_EXECUTABLE_ORIGIN='-Wl,-rpath,\$$ORIGIN[$]1'
      SET_SHARED_LIBRARY_NAME='-Wl,-soname=[$]1'
      SET_SHARED_LIBRARY_MAPFILE='-Wl,-version-script=[$]1'

      # arm specific settings
      if test "x$OPENJDK_TARGET_CPU" = "xarm"; then
        # '-Wl,-z,origin' isn't used on arm.
        SET_SHARED_LIBRARY_ORIGIN='-Wl,-rpath,\$$$$ORIGIN[$]1'
      else
        SET_SHARED_LIBRARY_ORIGIN="-Wl,-z,origin $SET_EXECUTABLE_ORIGIN"
      fi
    fi

  elif test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    C_FLAG_REORDER='-xF'
    SHARED_LIBRARY_FLAGS="-G"
    SET_EXECUTABLE_ORIGIN='-R\$$ORIGIN[$]1'
    SET_SHARED_LIBRARY_ORIGIN="$SET_EXECUTABLE_ORIGIN"
    SET_SHARED_LIBRARY_NAME='-h [$]1'
    SET_SHARED_LIBRARY_MAPFILE='-M[$]1'

  elif test "x$TOOLCHAIN_TYPE" = xxlc; then
    C_FLAG_REORDER=''
    SHARED_LIBRARY_FLAGS="-qmkshrobj -bM:SRE -bnoentry"
    SET_EXECUTABLE_ORIGIN=""
    SET_SHARED_LIBRARY_ORIGIN=''
    SET_SHARED_LIBRARY_NAME=''
    SET_SHARED_LIBRARY_MAPFILE=''

  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    C_FLAG_REORDER=''
    SHARED_LIBRARY_FLAGS="-dll"
    SET_EXECUTABLE_ORIGIN=''
    SET_SHARED_LIBRARY_ORIGIN=''
    SET_SHARED_LIBRARY_NAME=''
    SET_SHARED_LIBRARY_MAPFILE='-def:[$]1'
  fi

  AC_SUBST(C_FLAG_REORDER)
  AC_SUBST(SET_EXECUTABLE_ORIGIN)
  AC_SUBST(SET_SHARED_LIBRARY_ORIGIN)
  AC_SUBST(SET_SHARED_LIBRARY_NAME)
  AC_SUBST(SET_SHARED_LIBRARY_MAPFILE)
  AC_SUBST(SHARED_LIBRARY_FLAGS)
])

AC_DEFUN([FLAGS_SETUP_DEBUG_SYMBOLS],
[
  # By default don't set any specific assembler debug
  # info flags for toolchains unless we know they work.
  # See JDK-8207057.
  ASFLAGS_DEBUG_SYMBOLS=""
  # Debug symbols
  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    CFLAGS_DEBUG_SYMBOLS="-g"
    ASFLAGS_DEBUG_SYMBOLS="-g"
  elif test "x$TOOLCHAIN_TYPE" = xclang; then
    CFLAGS_DEBUG_SYMBOLS="-g"
    ASFLAGS_DEBUG_SYMBOLS="-g"
  elif test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    # -g0 enables debug symbols without disabling inlining.
    CFLAGS_DEBUG_SYMBOLS="-g0 -xs"
  elif test "x$TOOLCHAIN_TYPE" = xxlc; then
    CFLAGS_DEBUG_SYMBOLS="-g"
  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    CFLAGS_DEBUG_SYMBOLS="-Z7 -d2Zi+"
  fi

  AC_SUBST(CFLAGS_DEBUG_SYMBOLS)
  AC_SUBST(ASFLAGS_DEBUG_SYMBOLS)
])

AC_DEFUN([FLAGS_SETUP_WARNINGS],
[
  AC_ARG_ENABLE([warnings-as-errors], [AS_HELP_STRING([--disable-warnings-as-errors],
      [do not consider native warnings to be an error @<:@enabled@:>@])])

  # Set default value.
  if test "x$TOOLCHAIN_TYPE" = xxlc; then
    WARNINGS_AS_ERRORS=false
  else
    WARNINGS_AS_ERRORS=true
  fi

  AC_MSG_CHECKING([if native warnings are errors])
  if test "x$enable_warnings_as_errors" = "xyes"; then
    AC_MSG_RESULT([yes (explicitly set)])
    WARNINGS_AS_ERRORS=true
  elif test "x$enable_warnings_as_errors" = "xno"; then
    AC_MSG_RESULT([no (explicitly set)])
    WARNINGS_AS_ERRORS=false
  elif test "x$enable_warnings_as_errors" = "x"; then
    AC_MSG_RESULT([${WARNINGS_AS_ERRORS} (default)])
  else
    AC_MSG_ERROR([--enable-warnings-as-errors accepts no argument])
  fi

  AC_SUBST(WARNINGS_AS_ERRORS)

  case "${TOOLCHAIN_TYPE}" in
    microsoft)
      DISABLE_WARNING_PREFIX="-wd"
      CFLAGS_WARNINGS_ARE_ERRORS="-WX"
      ;;
    solstudio)
      DISABLE_WARNING_PREFIX="-erroff="
      CFLAGS_WARNINGS_ARE_ERRORS="-errtags -errwarn=%all"
      ;;
    gcc)
      DISABLE_WARNING_PREFIX="-Wno-"
      CFLAGS_WARNINGS_ARE_ERRORS="-Werror"
      BUILD_CC_DISABLE_WARNING_PREFIX="-Wno-"
      ;;
    clang)
      DISABLE_WARNING_PREFIX="-Wno-"
      CFLAGS_WARNINGS_ARE_ERRORS="-Werror"
      ;;
    xlc)
      DISABLE_WARNING_PREFIX="-qsuppress="
      CFLAGS_WARNINGS_ARE_ERRORS="-qhalt=w"
      ;;
  esac
  AC_SUBST(DISABLE_WARNING_PREFIX)
  AC_SUBST(BUILD_CC_DISABLE_WARNING_PREFIX)
  AC_SUBST(CFLAGS_WARNINGS_ARE_ERRORS)
])

AC_DEFUN([FLAGS_SETUP_QUALITY_CHECKS],
[
  # bounds, memory and behavior checking options
  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    case $DEBUG_LEVEL in
    release )
      # no adjustment
      ;;
    fastdebug )
      # no adjustment
      ;;
    slowdebug )
      # FIXME: By adding this to C(XX)FLAGS_DEBUG_OPTIONS/JVM_CFLAGS_SYMBOLS it
      # get's added conditionally on whether we produce debug symbols or not.
      # This is most likely not really correct.

      # Add runtime stack smashing and undefined behavior checks.
      CFLAGS_DEBUG_OPTIONS="-fstack-protector-all --param ssp-buffer-size=1"
      CXXFLAGS_DEBUG_OPTIONS="-fstack-protector-all --param ssp-buffer-size=1"

      JVM_CFLAGS_SYMBOLS="$JVM_CFLAGS_SYMBOLS -fstack-protector-all --param ssp-buffer-size=1"
      ;;
    esac
  fi

  # Extra flags needed when building optional static versions of certain
  # JDK libraries.
  STATIC_LIBS_CFLAGS="-DSTATIC_BUILD=1"
  if test "x$TOOLCHAIN_TYPE" = xgcc || test "x$TOOLCHAIN_TYPE" = xclang; then
    STATIC_LIBS_CFLAGS="$STATIC_LIBS_CFLAGS -ffunction-sections -fdata-sections"
  fi
  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    # Disable relax-relocation to enable compatibility with older linkers
    RELAX_RELOCATIONS_FLAG="-Xassembler -mrelax-relocations=no"
    FLAGS_COMPILER_CHECK_ARGUMENTS(ARGUMENT: [${RELAX_RELOCATIONS_FLAG}],
        IF_TRUE: [STATIC_LIBS_CFLAGS="$STATIC_LIBS_CFLAGS ${RELAX_RELOCATIONS_FLAG}"])
  fi
  AC_SUBST(STATIC_LIBS_CFLAGS)
])

AC_DEFUN([FLAGS_SETUP_OPTIMIZATION],
[
  if test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    CC_HIGHEST="-fns -fsimple -fsingle -xbuiltin=%all -xdepend -xrestrict -xlibmil"

    C_O_FLAG_HIGHEST_JVM="-xO4"
    C_O_FLAG_DEBUG_JVM=""
    C_O_FLAG_SIZE=""
    C_O_FLAG_DEBUG=""
    C_O_FLAG_NONE=""
    if test "x$OPENJDK_TARGET_CPU_ARCH" = "xx86"; then
      C_O_FLAG_HIGHEST="-xO4 -Wu,-O4~yz $CC_HIGHEST"
      C_O_FLAG_HI="-xO4 -Wu,-O4~yz"
      C_O_FLAG_NORM="-xO2 -Wu,-O2~yz"
    elif test "x$OPENJDK_TARGET_CPU_ARCH" = "xsparc"; then
      C_O_FLAG_HIGHEST="-xO4 -Wc,-Qrm-s -Wc,-Qiselect-T0 \
          -xprefetch=auto,explicit $CC_HIGHEST"
      C_O_FLAG_HI="-xO4 -Wc,-Qrm-s -Wc,-Qiselect-T0"
      C_O_FLAG_NORM="-xO2 -Wc,-Qrm-s -Wc,-Qiselect-T0"
    fi
  elif test "x$TOOLCHAIN_TYPE" = xgcc; then
    C_O_FLAG_HIGHEST_JVM="-O3"
    C_O_FLAG_HIGHEST="-O3"
    C_O_FLAG_HI="-O3"
    C_O_FLAG_NORM="-O2"
    C_O_FLAG_SIZE="-Os"
    C_O_FLAG_DEBUG="-O0"
    C_O_FLAG_DEBUG_JVM="-O0"
    C_O_FLAG_NONE="-O0"
  elif test "x$TOOLCHAIN_TYPE" = xclang; then
    if test "x$OPENJDK_TARGET_OS" = xmacosx; then
      # On MacOSX we optimize for size, something
      # we should do for all platforms?
      C_O_FLAG_HIGHEST_JVM="-Os"
      C_O_FLAG_HIGHEST="-Os"
      C_O_FLAG_HI="-Os"
      C_O_FLAG_NORM="-Os"
      C_O_FLAG_DEBUG_JVM=""
    else
      C_O_FLAG_HIGHEST_JVM="-O3"
      C_O_FLAG_HIGHEST="-O3"
      C_O_FLAG_HI="-O3"
      C_O_FLAG_NORM="-O2"
      C_O_FLAG_DEBUG_JVM="-O0"
    fi
    C_O_FLAG_SIZE="-Os"
    C_O_FLAG_DEBUG="-O0"
    C_O_FLAG_NONE="-O0"
  elif test "x$TOOLCHAIN_TYPE" = xxlc; then
    C_O_FLAG_HIGHEST_JVM="-O3 -qhot=level=1 -qinline -qinlglue"
    C_O_FLAG_HIGHEST="-O3 -qhot=level=1 -qinline -qinlglue"
    C_O_FLAG_HI="-O3 -qinline -qinlglue"
    C_O_FLAG_NORM="-O2"
    C_O_FLAG_DEBUG="-qnoopt"
    # FIXME: Value below not verified.
    C_O_FLAG_DEBUG_JVM=""
    C_O_FLAG_NONE="-qnoopt"
  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    C_O_FLAG_HIGHEST_JVM="-O2 -Oy-"
    C_O_FLAG_HIGHEST="-O2"
    C_O_FLAG_HI="-O1"
    C_O_FLAG_NORM="-O1"
    C_O_FLAG_DEBUG="-Od"
    C_O_FLAG_DEBUG_JVM=""
    C_O_FLAG_NONE="-Od"
    C_O_FLAG_SIZE="-Os"
  fi

  # Now copy to C++ flags
  CXX_O_FLAG_HIGHEST_JVM="$C_O_FLAG_HIGHEST_JVM"
  CXX_O_FLAG_HIGHEST="$C_O_FLAG_HIGHEST"
  CXX_O_FLAG_HI="$C_O_FLAG_HI"
  CXX_O_FLAG_NORM="$C_O_FLAG_NORM"
  CXX_O_FLAG_DEBUG="$C_O_FLAG_DEBUG"
  CXX_O_FLAG_DEBUG_JVM="$C_O_FLAG_DEBUG_JVM"
  CXX_O_FLAG_NONE="$C_O_FLAG_NONE"
  CXX_O_FLAG_SIZE="$C_O_FLAG_SIZE"

  if test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    # In solstudio, also add this to C (but not C++) flags...
    C_O_FLAG_HIGHEST="$C_O_FLAG_HIGHEST -xalias_level=basic"
  fi

  # Adjust optimization flags according to debug level.
  case $DEBUG_LEVEL in
    release )
      # no adjustment
      ;;
    fastdebug )
      # Not quite so much optimization
      C_O_FLAG_HI="$C_O_FLAG_NORM"
      CXX_O_FLAG_HI="$CXX_O_FLAG_NORM"
      ;;
    slowdebug )
      # Disable optimization
      C_O_FLAG_HIGHEST_JVM="$C_O_FLAG_DEBUG_JVM"
      C_O_FLAG_HIGHEST="$C_O_FLAG_DEBUG"
      C_O_FLAG_HI="$C_O_FLAG_DEBUG"
      C_O_FLAG_NORM="$C_O_FLAG_DEBUG"
      C_O_FLAG_SIZE="$C_O_FLAG_DEBUG"
      CXX_O_FLAG_HIGHEST_JVM="$CXX_O_FLAG_DEBUG_JVM"
      CXX_O_FLAG_HIGHEST="$CXX_O_FLAG_DEBUG"
      CXX_O_FLAG_HI="$CXX_O_FLAG_DEBUG"
      CXX_O_FLAG_NORM="$CXX_O_FLAG_DEBUG"
      CXX_O_FLAG_SIZE="$CXX_O_FLAG_DEBUG"
      ;;
  esac

  AC_SUBST(C_O_FLAG_HIGHEST_JVM)
  AC_SUBST(C_O_FLAG_HIGHEST)
  AC_SUBST(C_O_FLAG_HI)
  AC_SUBST(C_O_FLAG_NORM)
  AC_SUBST(C_O_FLAG_NONE)
  AC_SUBST(C_O_FLAG_SIZE)
  AC_SUBST(CXX_O_FLAG_HIGHEST_JVM)
  AC_SUBST(CXX_O_FLAG_HIGHEST)
  AC_SUBST(CXX_O_FLAG_HI)
  AC_SUBST(CXX_O_FLAG_NORM)
  AC_SUBST(CXX_O_FLAG_NONE)
  AC_SUBST(CXX_O_FLAG_SIZE)
])

AC_DEFUN([FLAGS_SETUP_CFLAGS],
[
  ### CFLAGS

  FLAGS_SETUP_CFLAGS_HELPER

  FLAGS_OS=$OPENJDK_TARGET_OS
  FLAGS_OS_TYPE=$OPENJDK_TARGET_OS_TYPE
  FLAGS_CPU=$OPENJDK_TARGET_CPU
  FLAGS_CPU_ARCH=$OPENJDK_TARGET_CPU_ARCH
  FLAGS_CPU_BITS=$OPENJDK_TARGET_CPU_BITS
  FLAGS_CPU_ENDIAN=$OPENJDK_TARGET_CPU_ENDIAN
  FLAGS_CPU_LEGACY=$OPENJDK_TARGET_CPU_LEGACY
  FLAGS_CPU_LEGACY_LIB=$OPENJDK_TARGET_CPU_LEGACY_LIB

  FLAGS_SETUP_CFLAGS_CPU_DEP([TARGET])

  # Repeat the check for the BUILD_CC and BUILD_CXX. Need to also reset CFLAGS
  # since any target specific flags will likely not work with the build compiler.
  CC_OLD="$CC"
  CXX_OLD="$CXX"
  CFLAGS_OLD="$CFLAGS"
  CXXFLAGS_OLD="$CXXFLAGS"
  CC="$BUILD_CC"
  CXX="$BUILD_CXX"
  CFLAGS=""
  CXXFLAGS=""

  FLAGS_OS=$OPENJDK_BUILD_OS
  FLAGS_OS_TYPE=$OPENJDK_BUILD_OS_TYPE
  FLAGS_CPU=$OPENJDK_BUILD_CPU
  FLAGS_CPU_ARCH=$OPENJDK_BUILD_CPU_ARCH
  FLAGS_CPU_BITS=$OPENJDK_BUILD_CPU_BITS
  FLAGS_CPU_ENDIAN=$OPENJDK_BUILD_CPU_ENDIAN
  FLAGS_CPU_LEGACY=$OPENJDK_BUILD_CPU_LEGACY
  FLAGS_CPU_LEGACY_LIB=$OPENJDK_BUILD_CPU_LEGACY_LIB

  FLAGS_SETUP_CFLAGS_CPU_DEP([BUILD], [OPENJDK_BUILD_], [BUILD_])

  CC="$CC_OLD"
  CXX="$CXX_OLD"
  CFLAGS="$CFLAGS_OLD"
  CXXFLAGS="$CXXFLAGS_OLD"

  # Tests are only ever compiled for TARGET
  CFLAGS_TESTLIB="$CFLAGS_JDKLIB"
  CXXFLAGS_TESTLIB="$CXXFLAGS_JDKLIB"
  CFLAGS_TESTEXE="$CFLAGS_JDKEXE"
  CXXFLAGS_TESTEXE="$CXXFLAGS_JDKEXE"

  AC_SUBST(CFLAGS_TESTLIB)
  AC_SUBST(CFLAGS_TESTEXE)
  AC_SUBST(CXXFLAGS_TESTLIB)
  AC_SUBST(CXXFLAGS_TESTEXE)
])

################################################################################
# platform independent
AC_DEFUN([FLAGS_SETUP_CFLAGS_HELPER],
[
  #### OS DEFINES, these should be independent on toolchain
  if test "x$OPENJDK_TARGET_OS" = xlinux; then
    CFLAGS_OS_DEF_JVM="-DLINUX"
    CFLAGS_OS_DEF_JDK="-D_GNU_SOURCE -D_REENTRANT -D_LARGEFILE64_SOURCE"
  elif test "x$OPENJDK_TARGET_OS" = xsolaris; then
    CFLAGS_OS_DEF_JVM="-DSOLARIS"
    CFLAGS_OS_DEF_JDK="-D__solaris__"
  elif test "x$OPENJDK_TARGET_OS" = xmacosx; then
    CFLAGS_OS_DEF_JVM="-D_ALLBSD_SOURCE -D_DARWIN_C_SOURCE -D_XOPEN_SOURCE"
    CFLAGS_OS_DEF_JDK="-D_ALLBSD_SOURCE -D_DARWIN_UNLIMITED_SELECT"
  elif test "x$OPENJDK_TARGET_OS" = xaix; then
    CFLAGS_OS_DEF_JVM="-DAIX"
  elif test "x$OPENJDK_TARGET_OS" = xbsd; then
    CFLAGS_OS_DEF_JDK="-D_ALLBSD_SOURCE"
  elif test "x$OPENJDK_TARGET_OS" = xwindows; then
    CFLAGS_OS_DEF_JVM="-D_WINDOWS -DWIN32 -D_JNI_IMPLEMENTATION_"
  fi

  CFLAGS_OS_DEF_JDK="$CFLAGS_OS_DEF_JDK -D$OPENJDK_TARGET_OS_UPPERCASE"

  #### GLOBAL DEFINES
  # Set some common defines. These works for all compilers, but assume
  # -D is universally accepted.

  # Always enable optional macros for VM.
  ALWAYS_CFLAGS_JVM="-D__STDC_FORMAT_MACROS -D__STDC_LIMIT_MACROS -D__STDC_CONSTANT_MACROS"

  # Setup some hard coded includes
  ALWAYS_CFLAGS_JDK=" \
      -I\$(SUPPORT_OUTPUTDIR)/modules_include/java.base \
      -I\$(SUPPORT_OUTPUTDIR)/modules_include/java.base/\$(OPENJDK_TARGET_OS_INCLUDE_SUBDIR) \
      -I${TOPDIR}/src/java.base/share/native/libjava \
      -I${TOPDIR}/src/java.base/$OPENJDK_TARGET_OS_TYPE/native/libjava \
      -I${TOPDIR}/src/hotspot/share/include \
      -I${TOPDIR}/src/hotspot/os/${HOTSPOT_TARGET_OS_TYPE}/include"

  ###############################################################################

  # Adjust flags according to debug level.
  # Setup debug/release defines
  if test "x$DEBUG_LEVEL" = xrelease; then
    DEBUG_CFLAGS_JDK="-DNDEBUG"
    if test "x$OPENJDK_TARGET_OS" = xsolaris; then
      DEBUG_CFLAGS_JDK="$DEBUG_CFLAGS_JDK -DTRIMMED"
    fi
  else
    DEBUG_CFLAGS_JDK="-DDEBUG"

    if test "x$TOOLCHAIN_TYPE" = xxlc; then
      # We need '-qminimaltoc' or '-qpic=large -bbigtoc' if the TOC overflows.
      # Hotspot now overflows its 64K TOC (currently only for debug),
      # so for debug we build with '-qpic=large -bbigtoc'.
      DEBUG_CFLAGS_JVM="-qpic=large"
    fi
  fi

  if test "x$DEBUG_LEVEL" != xrelease; then
    DEBUG_OPTIONS_FLAGS_JDK="$CFLAGS_DEBUG_OPTIONS"
    DEBUG_SYMBOLS_CFLAGS_JDK="$CFLAGS_DEBUG_SYMBOLS"
  fi

  #### TOOLCHAIN DEFINES

  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    ALWAYS_DEFINES_JVM="-D_GNU_SOURCE -D_REENTRANT"
  elif test "x$TOOLCHAIN_TYPE" = xclang; then
    ALWAYS_DEFINES_JVM="-D_GNU_SOURCE"
  elif test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    ALWAYS_DEFINES_JVM="-DSPARC_WORKS -D_Crun_inline_placement"
    ALWAYS_DEFINES_JDK="-DTRACING -DMACRO_MEMSYS_OPS -DBREAKPTS"
    ALWAYS_DEFINES_JDK_CXXONLY="-DCC_NOEX"
  elif test "x$TOOLCHAIN_TYPE" = xxlc; then
    ALWAYS_DEFINES_JVM="-D_REENTRANT"
    ALWAYS_DEFINES_JDK="-D_GNU_SOURCE -D_REENTRANT -D_LARGEFILE64_SOURCE -DSTDC"
  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    ALWAYS_DEFINES_JDK="-DWIN32_LEAN_AND_MEAN -D_CRT_SECURE_NO_DEPRECATE \
        -D_CRT_NONSTDC_NO_DEPRECATE -DWIN32 -DIAL"
    ALWAYS_DEFINES_JVM="-DNOMINMAX -DWIN32_LEAN_AND_MEAN"
  fi

  ###############################################################################
  #
  #
  # CFLAGS BASIC
  if test "x$TOOLCHAIN_TYPE" = xgcc || test "x$TOOLCHAIN_TYPE" = xclang; then
    # COMMON to gcc and clang
    TOOLCHAIN_CFLAGS_JVM="-pipe -fno-rtti -fno-exceptions \
        -fvisibility=hidden -fno-strict-aliasing -fno-omit-frame-pointer"
  fi

  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    TOOLCHAIN_CFLAGS_JVM="$TOOLCHAIN_CFLAGS_JVM -fcheck-new -fstack-protector"
    TOOLCHAIN_CFLAGS_JDK="-pipe -fstack-protector"
    # reduce lib size on s390x in link step, this needs also special compile flags
    if test "x$OPENJDK_TARGET_CPU" = xs390x; then
      TOOLCHAIN_CFLAGS_JVM="$TOOLCHAIN_CFLAGS_JVM -ffunction-sections -fdata-sections"
      TOOLCHAIN_CFLAGS_JDK="$TOOLCHAIN_CFLAGS_JDK -ffunction-sections -fdata-sections"
    fi
    # technically NOT for CXX (but since this gives *worse* performance, use
    # no-strict-aliasing everywhere!)
    TOOLCHAIN_CFLAGS_JDK_CONLY="-fno-strict-aliasing"

  elif test "x$TOOLCHAIN_TYPE" = xclang; then
    # Restrict the debug information created by Clang to avoid
    # too big object files and speed the build up a little bit
    # (see http://llvm.org/bugs/show_bug.cgi?id=7554)
    TOOLCHAIN_CFLAGS_JVM="$TOOLCHAIN_CFLAGS_JVM -flimit-debug-info"

    # In principle the stack alignment below is cpu- and ABI-dependent and
    # should agree with values of StackAlignmentInBytes in various
    # src/hotspot/cpu/*/globalDefinitions_*.hpp files, but this value currently
    # works for all platforms.
    TOOLCHAIN_CFLAGS_JVM="$TOOLCHAIN_CFLAGS_JVM -mno-omit-leaf-frame-pointer -mstack-alignment=16"

    if test "x$OPENJDK_TARGET_OS" = xlinux; then
      TOOLCHAIN_CFLAGS_JDK="-pipe"
      TOOLCHAIN_CFLAGS_JDK_CONLY="-fno-strict-aliasing" # technically NOT for CXX
    fi
  elif test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    TOOLCHAIN_CFLAGS_JDK="-mt"
    TOOLCHAIN_CFLAGS_JDK_CONLY="-xCC -Xa -v -W0,-noglobal" # C only
    TOOLCHAIN_CFLAGS_JDK_CXXONLY="-features=no%except -norunpath -xnolib" # CXX only
    TOOLCHAIN_CFLAGS_JVM="-template=no%extdef -features=no%split_init \
        -library=stlport4 -mt -features=no%except"
    if test "x$DEBUG_LEVEL" = xslowdebug; then
      # Previously -g was used instead of -g0 for slowdebug; this is equivalent
      # to setting +d.
      TOOLCHAIN_CFLAGS_JVM="$TOOLCHAIN_CFLAGS_JVM +d"
    fi

  elif test "x$TOOLCHAIN_TYPE" = xxlc; then
    TOOLCHAIN_CFLAGS_JDK="-qchars=signed -qfullpath -qsaveopt"  # add on both CFLAGS
    TOOLCHAIN_CFLAGS_JVM="-qtune=balanced \
        -qalias=noansi -qstrict -qtls=default -qlanglvl=c99vla \
        -qlanglvl=noredefmac -qnortti -qnoeh -qignerrno"
  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    TOOLCHAIN_CFLAGS_JVM="-nologo -MD -MP"
    TOOLCHAIN_CFLAGS_JDK="-nologo -MD -Zc:wchar_t-"
  fi

  # CFLAGS WARNINGS STUFF
  # Set JVM_CFLAGS warning handling
  if test "x$TOOLCHAIN_TYPE" = xgcc || test "x$TOOLCHAIN_TYPE" = xclang; then
    # COMMON to gcc and clang
    WARNING_CFLAGS_JVM="-Wpointer-arith -Wsign-compare -Wunused-function"
    if ! HOTSPOT_CHECK_JVM_VARIANT(zero); then
      # Non-zero builds have stricter warnings
      WARNING_CFLAGS_JVM="$WARNING_CFLAGS_JVM -Wundef -Wformat=2"
    fi

  fi
  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    WARNING_CFLAGS_JDK="-Wall -Wextra -Wno-unused -Wno-unused-parameter -Wformat=2"
    WARNING_CFLAGS_JVM="$WARNING_CFLAGS_JVM -Wunused-value -Woverloaded-virtual"

    if ! HOTSPOT_CHECK_JVM_VARIANT(zero); then
      # Non-zero builds have stricter warnings
      WARNING_CFLAGS_JVM="$WARNING_CFLAGS_JVM -Wreturn-type"
    fi
  elif test "x$TOOLCHAIN_TYPE" = xclang; then
    WARNING_CFLAGS_JVM="$WARNING_CFLAGS_JVM -Wno-deprecated"
    if test "x$OPENJDK_TARGET_OS" = xlinux; then
      WARNING_CFLAGS_JVM="$WARNING_CFLAGS_JVM -Wno-sometimes-uninitialized"
      WARNING_CFLAGS_JDK="-Wall -Wextra -Wno-unused -Wno-unused-parameter -Wformat=2"
    fi
  elif test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    WARNING_CFLAGS_JDK_CONLY="-errshort=tags"
    WARNING_CFLAGS_JDK_CXXONLY="+w"
    WARNING_CFLAGS_JDK="-errtags=yes -errfmt"
  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    WARNING_CFLAGS="-W3"
    WARNING_CFLAGS_JDK="-wd4800"
    WARNING_CFLAGS_JVM="-wd4800"
  fi

  # Set some additional per-OS defines.

  # Additional macosx handling
  if test "x$OPENJDK_TARGET_OS" = xmacosx; then
    OS_CFLAGS="-DMAC_OS_X_VERSION_MIN_REQUIRED=$MACOSX_VERSION_MIN_NODOTS \
        -mmacosx-version-min=$MACOSX_VERSION_MIN"

    if test -n "$MACOSX_VERSION_MAX"; then
        OS_CFLAGS="$OS_CFLAGS \
            -DMAC_OS_X_VERSION_MAX_ALLOWED=$MACOSX_VERSION_MAX_NODOTS"
    fi
  fi

  # Where does this really belong??
  if test "x$TOOLCHAIN_TYPE" = xgcc || test "x$TOOLCHAIN_TYPE" = xclang; then
    PICFLAG="-fPIC"
    PIEFLAG="-fPIE"
  elif test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    PICFLAG="-KPIC"
  elif test "x$TOOLCHAIN_TYPE" = xxlc; then
    # '-qpic' defaults to 'qpic=small'. This means that the compiler generates only
    # one instruction for accessing the TOC. If the TOC grows larger than 64K, the linker
    # will have to patch this single instruction with a call to some out-of-order code which
    # does the load from the TOC. This is of course slower, and we also would have
    # to use '-bbigtoc' for linking anyway so we could also change the PICFLAG to 'qpic=large'.
    # With 'qpic=large' the compiler will by default generate a two-instruction sequence which
    # can be patched directly by the linker and does not require a jump to out-of-order code.
    #
    # Since large TOC causes perf. overhead, only pay it where we must. Currently this is
    # for all libjvm variants (both gtest and normal) but no other binaries. So, build
    # libjvm with -qpic=large and link with -bbigtoc.
    JVM_PICFLAG="-qpic=large"
    JDK_PICFLAG="-qpic"
  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    PICFLAG=""
  fi

  if test "x$TOOLCHAIN_TYPE" != xxlc; then
    JVM_PICFLAG="$PICFLAG"
    JDK_PICFLAG="$PICFLAG"
  fi

  if test "x$OPENJDK_TARGET_OS" = xmacosx; then
    # Linking is different on MacOSX
    JDK_PICFLAG=''
    if test "x$STATIC_BUILD" = xtrue; then
      JVM_PICFLAG=""
    fi
  fi

  # Optional POSIX functionality needed by the JVM
  #
  # Check if clock_gettime is available and in which library. This indicates
  # availability of CLOCK_MONOTONIC for hotspot. But we don't need to link, so
  # don't let it update LIBS.
  save_LIBS="$LIBS"
  AC_SEARCH_LIBS(clock_gettime, rt, [HAS_CLOCK_GETTIME=true], [])
  if test "x$LIBS" = "x-lrt "; then
    CLOCK_GETTIME_IN_LIBRT=true
  fi
  LIBS="$save_LIBS"

  if test "x$HAS_CLOCK_GETTIME" = "xtrue"; then
    OS_CFLAGS_JVM="$OS_CFLAGS_JVM -DSUPPORTS_CLOCK_MONOTONIC"
    if test "x$CLOCK_GETTIME_IN_LIBRT" = "xtrue"; then
      OS_CFLAGS_JVM="$OS_CFLAGS_JVM -DNEEDS_LIBRT"
    fi
  fi
])

################################################################################
# $1 - Either BUILD or TARGET to pick the correct OS/CPU variables to check
#      conditionals against.
# $2 - Optional prefix for each variable defined.
# $3 - Optional prefix for compiler variables (either BUILD_ or nothing).
AC_DEFUN([FLAGS_SETUP_CFLAGS_CPU_DEP],
[
  #### CPU DEFINES, these should (in theory) be independent on toolchain

  # Setup target CPU
  # Setup endianness
  if test "x$FLAGS_CPU_ENDIAN" = xlittle; then
    $1_DEFINES_CPU_JVM="-DVM_LITTLE_ENDIAN"
  fi
  if test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    # The macro _LITTLE_ENDIAN needs to be defined the same to avoid the
    #   Sun C compiler warning message: warning: macro redefined: _LITTLE_ENDIAN
    if test "x$FLAGS_CPU_ENDIAN" = xlittle; then
      $1_DEFINES_CPU_JDK="-D_LITTLE_ENDIAN="
    else
      $1_DEFINES_CPU_JDK="-D_BIG_ENDIAN="
    fi
  else
    if test "x$FLAGS_CPU_ENDIAN" = xlittle; then
      $1_DEFINES_CPU_JDK="-D_LITTLE_ENDIAN"
    else
      $1_DEFINES_CPU_JDK="-D_BIG_ENDIAN"
    fi
  fi

  # setup CPU bit size
  $1_DEFINES_CPU_JDK="${$1_DEFINES_CPU_JDK} -DARCH='\"$FLAGS_CPU_LEGACY\"' \
      -D$FLAGS_CPU_LEGACY"

  if test "x$FLAGS_CPU_BITS" = x64; then
    # -D_LP64=1 is only set on linux and mac. Setting on windows causes diff in
    # unpack200.exe.
    if test "x$FLAGS_OS" = xlinux || test "x$FLAGS_OS" = xmacosx; then
      $1_DEFINES_CPU_JDK="${$1_DEFINES_CPU_JDK} -D_LP64=1"
    fi
    if test "x$FLAGS_OS" != xsolaris && test "x$FLAGS_OS" != xaix; then
      # Solaris does not have _LP64=1 in the old build.
      # xlc on AIX defines _LP64=1 by default and issues a warning if we redefine it.
      $1_DEFINES_CPU_JVM="${$1_DEFINES_CPU_JVM} -D_LP64=1"
    fi
  fi

  # toolchain dependend, per-cpu
  if test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    if test "x$FLAGS_CPU_ARCH" = xx86; then
      $1_DEFINES_CPU_JDK="${$1_DEFINES_CPU_JDK} -DcpuIntel -Di586 -D$FLAGS_CPU_LEGACY_LIB"
    fi
  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    if test "x$FLAGS_CPU" = xx86_64; then
      $1_DEFINES_CPU_JDK="${$1_DEFINES_CPU_JDK} -D_AMD64_ -Damd64"
    else
      $1_DEFINES_CPU_JDK="${$1_DEFINES_CPU_JDK} -D_X86_ -Dx86"
    fi
  fi

  # CFLAGS PER CPU
  if test "x$TOOLCHAIN_TYPE" = xgcc || test "x$TOOLCHAIN_TYPE" = xclang; then
    # COMMON to gcc and clang
    AC_MSG_CHECKING([if $1 is x86])
    if test "x$FLAGS_CPU" = xx86; then
      AC_MSG_RESULT([yes])
      AC_MSG_CHECKING([if control flow protection is enabled by additional compiler flags])
      if echo "${EXTRA_CFLAGS}${EXTRA_CXXFLAGS}${EXTRA_ASFLAGS}" | ${GREP} -q 'fcf-protection' ; then
        # cf-protection requires CMOV and thus i686
        $1_CFLAGS_CPU="-march=i686"
        AC_MSG_RESULT([yes, forcing ${$1_CFLAGS_CPU}])
      else
        # Force compatibility with i586 on 32 bit intel platforms.
        $1_CFLAGS_CPU="-march=i586"
        AC_MSG_RESULT([no, forcing ${$1_CFLAGS_CPU}])
      fi
    else
      AC_MSG_RESULT([no])
    fi
  fi

  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    if test "x$FLAGS_CPU" = xarm; then
      # -Wno-psabi to get rid of annoying "note: the mangling of 'va_list' has changed in GCC 4.4"
      $1_CFLAGS_CPU="-fsigned-char -Wno-psabi $ARM_ARCH_TYPE_FLAGS $ARM_FLOAT_TYPE_FLAGS -DJDK_ARCH_ABI_PROP_NAME='\"\$(JDK_ARCH_ABI_PROP_NAME)\"'"
      $1_CFLAGS_CPU_JVM="-DARM"
    elif test "x$FLAGS_CPU" = xaarch64; then
      if test "x$HOTSPOT_TARGET_CPU_PORT" = xarm64; then
        $1_CFLAGS_CPU_JVM="-fsigned-char -DARM"
      fi
    elif test "x$FLAGS_CPU_ARCH" = xppc; then
      $1_CFLAGS_CPU_JVM="-minsert-sched-nops=regroup_exact -mno-multiple -mno-string"
      if test "x$FLAGS_CPU" = xppc64; then
        # -mminimal-toc fixes `relocation truncated to fit' error for gcc 4.1.
        # Use ppc64 instructions, but schedule for power5
        $1_CFLAGS_CPU_JVM="${$1_CFLAGS_CPU_JVM} -mminimal-toc -mcpu=powerpc64 -mtune=power5"
      elif test "x$FLAGS_CPU" = xppc64le; then
        # Little endian machine uses ELFv2 ABI.
        # Use Power8, this is the first CPU to support PPC64 LE with ELFv2 ABI.
        $1_CFLAGS_CPU_JVM="${$1_CFLAGS_CPU_JVM} -DABI_ELFv2 -mcpu=power8 -mtune=power8"
      fi
    elif test "x$FLAGS_CPU" = xs390x; then
      $1_CFLAGS_CPU="-mbackchain -march=z10"
    fi

    if test "x$FLAGS_CPU_ARCH" != xarm &&  test "x$FLAGS_CPU_ARCH" != xppc; then
      # for all archs except arm and ppc, prevent gcc to omit frame pointer
      $1_CFLAGS_CPU_JDK="${$1_CFLAGS_CPU_JDK} -fno-omit-frame-pointer"
    fi

    $1_CXXSTD_CXXFLAG="-std=gnu++98"
    FLAGS_CXX_COMPILER_CHECK_ARGUMENTS(ARGUMENT: [${$1_CXXSTD_CXXFLAG} -Werror],
        PREFIX: $3, IF_FALSE: [$1_CXXSTD_CXXFLAG=""])
    $1_TOOLCHAIN_CFLAGS_JDK_CXXONLY="${$1_CXXSTD_CXXFLAG}"
    $1_TOOLCHAIN_CFLAGS_JVM="${$1_TOOLCHAIN_CFLAGS_JVM} ${$1_CXXSTD_CXXFLAG}"
    $2ADLC_CXXFLAG="${$1_CXXSTD_CXXFLAG}"

  elif test "x$TOOLCHAIN_TYPE" = xclang; then
    if test "x$FLAGS_OS" = xlinux; then
      # ppc test not really needed for clang
      if test "x$FLAGS_CPU_ARCH" != xarm &&  test "x$FLAGS_CPU_ARCH" != xppc; then
        # for all archs except arm and ppc, prevent gcc to omit frame pointer
        $1_CFLAGS_CPU_JDK="${$1_CFLAGS_CPU_JDK} -fno-omit-frame-pointer"
      fi
    fi

  elif test "x$TOOLCHAIN_TYPE" = xsolstudio; then
    if test "x$FLAGS_CPU" = xx86_64; then
      # NOTE: -xregs=no%frameptr is supposed to be default on x64
      $1_CFLAGS_CPU_JDK="-xregs=no%frameptr"
    elif test "x$FLAGS_CPU" = xsparcv9; then
      $1_CFLAGS_CPU_JVM="-xarch=sparc"
      $1_CFLAGS_CPU_JDK_LIBONLY="-xregs=no%appl"
    fi

  elif test "x$TOOLCHAIN_TYPE" = xxlc; then
    if test "x$FLAGS_CPU" = xppc64; then
      $1_CFLAGS_CPU_JVM="-qarch=ppc64"
    fi

  elif test "x$TOOLCHAIN_TYPE" = xmicrosoft; then
    if test "x$FLAGS_CPU" = xx86; then
      $1_CFLAGS_CPU_JVM="-arch:IA32"
    elif test "x$OPENJDK_TARGET_CPU" = xx86_64; then
      if test "x$DEBUG_LEVEL" != xrelease; then
        # NOTE: This is probably redundant; -homeparams is default on
        # non-release builds.
        $1_CFLAGS_CPU_JVM="-homeparams"
      fi
    fi
  fi

  if test "x$TOOLCHAIN_TYPE" = xgcc; then
    FLAGS_SETUP_GCC6_COMPILER_FLAGS($1, $3)
    $1_TOOLCHAIN_CFLAGS="${$1_GCC6_CFLAGS}"

    $1_WARNING_CFLAGS_JVM="-Wno-format-zero-length -Wtype-limits -Wuninitialized"
  fi

  # Prevent the __FILE__ macro from generating absolute paths into the built
  # binaries. Depending on toolchain, different mitigations are possible.
  # * GCC and Clang of new enough versions have -fmacro-prefix-map.
  # * For most other toolchains, supplying all source files and -I flags as
  #   relative paths fixes the issue.
  FILE_MACRO_CFLAGS=
  if test "x$ALLOW_ABSOLUTE_PATHS_IN_OUTPUT" = "xfalse"; then
    if test "x$TOOLCHAIN_TYPE" = xgcc || test "x$TOOLCHAIN_TYPE" = xclang; then
      # Check if compiler supports -fmacro-prefix-map. If so, use that to make
      # the __FILE__ macro resolve to paths relative to the workspace root.
      workspace_root_trailing_slash="${WORKSPACE_ROOT%/}/"
      FILE_MACRO_CFLAGS="-fmacro-prefix-map=${workspace_root_trailing_slash}="
      FLAGS_COMPILER_CHECK_ARGUMENTS(ARGUMENT: [${FILE_MACRO_CFLAGS}],
          PREFIX: $3,
          IF_FALSE: [
              FILE_MACRO_CFLAGS=
          ]
      )
    fi
  fi
  AC_SUBST(FILE_MACRO_CFLAGS)

  # EXPORT to API
  CFLAGS_JVM_COMMON="$ALWAYS_CFLAGS_JVM $ALWAYS_DEFINES_JVM \
      $TOOLCHAIN_CFLAGS_JVM ${$1_TOOLCHAIN_CFLAGS_JVM} \
      $OS_CFLAGS $OS_CFLAGS_JVM $CFLAGS_OS_DEF_JVM $DEBUG_CFLAGS_JVM \
      $WARNING_CFLAGS $WARNING_CFLAGS_JVM $JVM_PICFLAG $FILE_MACRO_CFLAGS"

  CFLAGS_JDK_COMMON="$ALWAYS_CFLAGS_JDK $ALWAYS_DEFINES_JDK $TOOLCHAIN_CFLAGS_JDK \
      $OS_CFLAGS $CFLAGS_OS_DEF_JDK $DEBUG_CFLAGS_JDK $DEBUG_OPTIONS_FLAGS_JDK \
      $WARNING_CFLAGS $WARNING_CFLAGS_JDK $DEBUG_SYMBOLS_CFLAGS_JDK \
      $FILE_MACRO_CFLAGS"

  # Use ${$2EXTRA_CFLAGS} to block EXTRA_CFLAGS to be added to build flags.
  # (Currently we don't have any OPENJDK_BUILD_EXTRA_CFLAGS, but that might
  # change in the future.)

  CFLAGS_JDK_COMMON_CONLY="$TOOLCHAIN_CFLAGS_JDK_CONLY  \
      $WARNING_CFLAGS_JDK_CONLY ${$2EXTRA_CFLAGS}"
  CFLAGS_JDK_COMMON_CXXONLY="$ALWAYS_DEFINES_JDK_CXXONLY \
      $TOOLCHAIN_CFLAGS_JDK_CXXONLY \
      ${$1_TOOLCHAIN_CFLAGS_JDK_CXXONLY} \
      $WARNING_CFLAGS_JDK_CXXONLY ${$2EXTRA_CXXFLAGS}"

  $1_CFLAGS_JVM="${$1_DEFINES_CPU_JVM} ${$1_CFLAGS_CPU} ${$1_CFLAGS_CPU_JVM} ${$1_TOOLCHAIN_CFLAGS} ${$1_WARNING_CFLAGS_JVM}"
  $1_CFLAGS_JDK="${$1_DEFINES_CPU_JDK} ${$1_CFLAGS_CPU} ${$1_CFLAGS_CPU_JDK} ${$1_TOOLCHAIN_CFLAGS}"

  $2JVM_CFLAGS="$CFLAGS_JVM_COMMON ${$1_CFLAGS_JVM} ${$2EXTRA_CXXFLAGS}"

  $2CFLAGS_JDKEXE="$CFLAGS_JDK_COMMON $CFLAGS_JDK_COMMON_CONLY ${$1_CFLAGS_JDK} $PIEFLAG"
  $2CXXFLAGS_JDKEXE="$CFLAGS_JDK_COMMON $CFLAGS_JDK_COMMON_CXXONLY ${$1_CFLAGS_JDK} $PIEFLAG"
  $2CFLAGS_JDKLIB="$CFLAGS_JDK_COMMON $CFLAGS_JDK_COMMON_CONLY ${$1_CFLAGS_JDK} \
      $JDK_PICFLAG ${$1_CFLAGS_CPU_JDK_LIBONLY}"
  $2CXXFLAGS_JDKLIB="$CFLAGS_JDK_COMMON $CFLAGS_JDK_COMMON_CXXONLY ${$1_CFLAGS_JDK} \
      $JDK_PICFLAG ${$1_CFLAGS_CPU_JDK_LIBONLY}"

  AC_SUBST($2JVM_CFLAGS)
  AC_SUBST($2CFLAGS_JDKLIB)
  AC_SUBST($2CFLAGS_JDKEXE)
  AC_SUBST($2CXXFLAGS_JDKLIB)
  AC_SUBST($2CXXFLAGS_JDKEXE)
  AC_SUBST($2ADLC_CXXFLAG)

  COMPILER_FP_CONTRACT_OFF_FLAG="-ffp-contract=off"
  # Check that the compiler supports -ffp-contract=off flag
  # Set FDLIBM_CFLAGS to -ffp-contract=off if it does. Empty
  # otherwise.
  # These flags are required for GCC-based builds of
  # fdlibm with optimization without losing precision.
  # Notably, -ffp-contract=off needs to be added for GCC >= 4.6.
  if test "x$TOOLCHAIN_TYPE" = xgcc || test "x$TOOLCHAIN_TYPE" = xclang; then
    FLAGS_COMPILER_CHECK_ARGUMENTS(ARGUMENT: [${COMPILER_FP_CONTRACT_OFF_FLAG}],
        PREFIX: $3,
        IF_TRUE: [$2FDLIBM_CFLAGS=${COMPILER_FP_CONTRACT_OFF_FLAG}],
        IF_FALSE: [$2FDLIBM_CFLAGS=""])
  fi
  AC_SUBST($2FDLIBM_CFLAGS)
])

# FLAGS_SETUP_GCC6_COMPILER_FLAGS([PREFIX])
# Arguments:
# $1 - Prefix for each variable defined.
# $2 - Prefix for compiler variables (either BUILD_ or nothing).
AC_DEFUN([FLAGS_SETUP_GCC6_COMPILER_FLAGS],
[
  # These flags are required for GCC 6 builds as undefined behaviour in OpenJDK code
  # runs afoul of the more aggressive versions of these optimisations.
  # Notably, value range propagation now assumes that the this pointer of C++
  # member functions is non-null.
  NO_DELETE_NULL_POINTER_CHECKS_CFLAG="-fno-delete-null-pointer-checks"
  FLAGS_COMPILER_CHECK_ARGUMENTS(ARGUMENT: [$NO_DELETE_NULL_POINTER_CHECKS_CFLAG -Werror],
      PREFIX: $2, IF_FALSE: [NO_DELETE_NULL_POINTER_CHECKS_CFLAG=""])
  NO_LIFETIME_DSE_CFLAG="-fno-lifetime-dse"
  FLAGS_COMPILER_CHECK_ARGUMENTS(ARGUMENT: [$NO_LIFETIME_DSE_CFLAG -Werror],
      PREFIX: $2, IF_FALSE: [NO_LIFETIME_DSE_CFLAG=""])
  $1_GCC6_CFLAGS="${NO_DELETE_NULL_POINTER_CHECKS_CFLAG} ${NO_LIFETIME_DSE_CFLAG}"
])

# Documentation on common flags used for solstudio in HIGHEST.
#
# WARNING: Use of OPTIMIZATION_LEVEL=HIGHEST in your Makefile needs to be
#          done with care, there are some assumptions below that need to
#          be understood about the use of pointers, and IEEE behavior.
#
# -fns: Use non-standard floating point mode (not IEEE 754)
# -fsimple: Do some simplification of floating point arithmetic (not IEEE 754)
# -fsingle: Use single precision floating point with 'float'
# -xalias_level=basic: Assume memory references via basic pointer types do not alias
#   (Source with excessing pointer casting and data access with mixed
#    pointer types are not recommended)
# -xbuiltin=%all: Use intrinsic or inline versions for math/std functions
#   (If you expect perfect errno behavior, do not use this)
# -xdepend: Loop data dependency optimizations (need -xO3 or higher)
# -xrestrict: Pointer parameters to functions do not overlap
#   (Similar to -xalias_level=basic usage, but less obvious sometimes.
#    If you pass in multiple pointers to the same data, do not use this)
# -xlibmil: Inline some library routines
#   (If you expect perfect errno behavior, do not use this)
# -xlibmopt: Use optimized math routines (CURRENTLY DISABLED)
#   (If you expect perfect errno behavior, do not use this)
#  Can cause undefined external on Solaris 8 X86 on __sincos, removing for now
