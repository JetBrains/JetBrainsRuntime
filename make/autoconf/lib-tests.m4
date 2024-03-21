#
# Copyright (c) 2018, 2022, Oracle and/or its affiliates. All rights reserved.
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
# Setup libraries and functionalities needed to test the JDK.
################################################################################

# Minimum supported version
JTREG_MINIMUM_VERSION=7.3.1

###############################################################################
#
# Check for graalunit libs, needed for running graalunit tests.
#
AC_DEFUN_ONCE([LIB_TESTS_SETUP_GRAALUNIT],
[
  AC_ARG_WITH(graalunit-lib, [AS_HELP_STRING([--with-graalunit-lib],
      [specify location of 3rd party libraries used by Graal unit tests])])

  GRAALUNIT_LIB=
  if test "x${with_graalunit_lib}" != x; then
    AC_MSG_CHECKING([for graalunit libs])
    if test "x${with_graalunit_lib}" = xno; then
      AC_MSG_RESULT([disabled, graalunit tests can not be run])
    elif test "x${with_graalunit_lib}" = xyes; then
      AC_MSG_RESULT([not specified])
      AC_MSG_ERROR([You must specify the path to 3rd party libraries used by Graal unit tests])
    else
      GRAALUNIT_LIB="${with_graalunit_lib}"
      if test ! -d "${GRAALUNIT_LIB}"; then
        AC_MSG_RESULT([no])
        AC_MSG_ERROR([Could not find graalunit 3rd party libraries as specified. (${with_graalunit_lib})])
      else
        AC_MSG_RESULT([$GRAALUNIT_LIB])
      fi
    fi
  fi

  UTIL_FIXUP_PATH([GRAALUNIT_LIB])
  AC_SUBST(GRAALUNIT_LIB)
])

# Setup the JTReg Regression Test Harness.
AC_DEFUN_ONCE([LIB_TESTS_SETUP_JTREG],
[
  AC_ARG_WITH(jtreg, [AS_HELP_STRING([--with-jtreg],
      [Regression Test Harness @<:@probed@:>@])])

  if test "x$with_jtreg" = xno; then
    # jtreg disabled
    AC_MSG_CHECKING([for jtreg test harness])
    AC_MSG_RESULT([no, disabled])
  elif test "x$with_jtreg" != xyes && test "x$with_jtreg" != x; then
    # An explicit path is specified, use it.
    JT_HOME="$with_jtreg"
    UTIL_FIXUP_PATH([JT_HOME])
    if test ! -d "$JT_HOME"; then
      AC_MSG_ERROR([jtreg home directory from --with-jtreg=$with_jtreg does not exist])
    fi

    if test ! -e "$JT_HOME/lib/jtreg.jar"; then
      AC_MSG_ERROR([jtreg home directory from --with-jtreg=$with_jtreg is not a valid jtreg home])
    fi

    JTREGEXE="$JT_HOME/bin/jtreg"
    if test ! -x "$JTREGEXE"; then
      AC_MSG_ERROR([jtreg home directory from --with-jtreg=$with_jtreg does not contain valid jtreg executable])
    fi

    AC_MSG_CHECKING([for jtreg test harness])
    AC_MSG_RESULT([$JT_HOME])
  else
    # Try to locate jtreg
    if test "x$JT_HOME" != x; then
      # JT_HOME set in environment, use it
      if test ! -d "$JT_HOME"; then
        AC_MSG_WARN([Ignoring JT_HOME pointing to invalid directory: $JT_HOME])
        JT_HOME=
      else
        if test ! -e "$JT_HOME/lib/jtreg.jar"; then
          AC_MSG_WARN([Ignoring JT_HOME which is not a valid jtreg home: $JT_HOME])
          JT_HOME=
        elif test ! -x "$JT_HOME/bin/jtreg"; then
          AC_MSG_WARN([Ignoring JT_HOME which does not contain valid jtreg executable: $JT_HOME])
          JT_HOME=
        else
          JTREGEXE="$JT_HOME/bin/jtreg"
          AC_MSG_NOTICE([Located jtreg using JT_HOME from environment])
        fi
      fi
    fi

    if test "x$JT_HOME" = x; then
      # JT_HOME is not set in environment, or was deemed invalid.
      # Try to find jtreg on path
      UTIL_LOOKUP_PROGS(JTREGEXE, jtreg)
      if test "x$JTREGEXE" != x; then
        # That's good, now try to derive JT_HOME
        JT_HOME=`(cd $($DIRNAME $JTREGEXE)/.. && pwd)`
        if test ! -e "$JT_HOME/lib/jtreg.jar"; then
          AC_MSG_WARN([Ignoring jtreg from path since a valid jtreg home cannot be found])
          JT_HOME=
          JTREGEXE=
        else
          AC_MSG_NOTICE([Located jtreg using jtreg executable in path])
        fi
      fi
    fi

    AC_MSG_CHECKING([for jtreg test harness])
    if test "x$JT_HOME" != x; then
      AC_MSG_RESULT([$JT_HOME])
    else
      AC_MSG_RESULT([no, not found])

      if test "x$with_jtreg" = xyes; then
        AC_MSG_ERROR([--with-jtreg was specified, but no jtreg found.])
      fi
    fi
  fi

  UTIL_FIXUP_EXECUTABLE(JTREGEXE)
  UTIL_FIXUP_PATH(JT_HOME)
  AC_SUBST(JT_HOME)

  # Verify jtreg version
  if test "x$JT_HOME" != x; then
    AC_MSG_CHECKING([jtreg version number])
    # jtreg -version looks like this: "jtreg 6.1+1-19"
    # Extract actual version part ("6.1" in this case)
    jtreg_version_full=`$JAVA -jar $JT_HOME/lib/jtreg.jar -version | $HEAD -n 1 | $CUT -d ' ' -f 2`
    jtreg_version=${jtreg_version_full/%+*}
    AC_MSG_RESULT([$jtreg_version])

    # This is a simplified version of TOOLCHAIN_CHECK_COMPILER_VERSION
    comparable_actual_version=`$AWK -F. '{ printf("%05d%05d%05d%05d\n", [$]1, [$]2, [$]3, [$]4) }' <<< "$jtreg_version"`
    comparable_minimum_version=`$AWK -F. '{ printf("%05d%05d%05d%05d\n", [$]1, [$]2, [$]3, [$]4) }' <<< "$JTREG_MINIMUM_VERSION"`
    if test $comparable_actual_version -lt $comparable_minimum_version ; then
      AC_MSG_ERROR([jtreg version is too old, at least version $JTREG_MINIMUM_VERSION is required])
    fi
  fi

  AC_SUBST(JTREGEXE)
])

# Setup the JIB dependency resolver
AC_DEFUN_ONCE([LIB_TESTS_SETUP_JIB],
[
  AC_ARG_WITH(jib, [AS_HELP_STRING([--with-jib],
      [Jib dependency management tool @<:@not used@:>@])])

  if test "x$with_jib" = xno || test "x$with_jib" = x; then
    # jib disabled
    AC_MSG_CHECKING([for jib])
    AC_MSG_RESULT(no)
  elif test "x$with_jib" = xyes; then
    AC_MSG_ERROR([Must supply a value to --with-jib])
  else
    JIB_HOME="${with_jib}"
    AC_MSG_CHECKING([for jib])
    AC_MSG_RESULT(${JIB_HOME})
    if test ! -d "${JIB_HOME}"; then
      AC_MSG_ERROR([--with-jib must be a directory])
    fi
    JIB_JAR=$(ls ${JIB_HOME}/lib/jib-*.jar)
    if test ! -f "${JIB_JAR}"; then
      AC_MSG_ERROR([Could not find jib jar file in ${JIB_HOME}])
    fi
  fi

  AC_SUBST(JIB_HOME)
])

################################################################################
#
# Check if building of the jtreg failure handler should be enabled.
#
AC_DEFUN_ONCE([LIB_TESTS_ENABLE_DISABLE_FAILURE_HANDLER],
[
  AC_ARG_ENABLE([jtreg-failure-handler], [AS_HELP_STRING([--enable-jtreg-failure-handler],
    [forces build of the jtreg failure handler to be enabled, missing dependencies
     become fatal errors. Default is auto, where the failure handler is built if all
     dependencies are present and otherwise just disabled.])])

  AC_MSG_CHECKING([if jtreg failure handler should be built])

  if test "x$enable_jtreg_failure_handler" = "xyes"; then
    if test "x$JT_HOME" = "x"; then
      AC_MSG_ERROR([Cannot enable jtreg failure handler without jtreg.])
    else
      BUILD_FAILURE_HANDLER=true
      AC_MSG_RESULT([yes, forced])
    fi
  elif test "x$enable_jtreg_failure_handler" = "xno"; then
    BUILD_FAILURE_HANDLER=false
    AC_MSG_RESULT([no, forced])
  elif test "x$enable_jtreg_failure_handler" = "xauto" \
      || test "x$enable_jtreg_failure_handler" = "x"; then
    if test "x$JT_HOME" = "x"; then
      BUILD_FAILURE_HANDLER=false
      AC_MSG_RESULT([no, missing jtreg])
    else
      BUILD_FAILURE_HANDLER=true
      AC_MSG_RESULT([yes, jtreg present])
    fi
  else
    AC_MSG_ERROR([Invalid value for --enable-jtreg-failure-handler: $enable_jtreg_failure_handler])
  fi

  AC_SUBST(BUILD_FAILURE_HANDLER)
])
