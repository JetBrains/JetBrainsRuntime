################################################################################
# Setup glib
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_GLIB],
[
  AC_ARG_WITH(glib, [AS_HELP_STRING([--with-glib],
      [specify prefix directory for the glib package
      (expecting the headers under PATH/include); required for atk-wrapper to work])])
  AC_ARG_WITH(glib-include, [AS_HELP_STRING([--with-glib-include],
      [specify directory for the glib include files])])
  AC_ARG_WITH(glibconfig, [AS_HELP_STRING([--with-glibconfig],
        [specify prefix directory for the glibconfig package
        (expecting the headers under PATH/include); required for atk-wrapper to work])])
  AC_ARG_WITH(glibconfig-include, [AS_HELP_STRING([--with-glibconfig-include],
        [specify directory for the glibconfig include files])])

  if test "x$NEEDS_LIB_GLIB" = xfalse || test "x${with_glib}" = xno || \
      test "x${with_glib_include}" = xno || \
      test "x${with_glibconfig}" = xno || test "x${with_glibconfig_include}" = xno; then
    if (test "x${with_glib}" != x && test "x${with_glib}" != xno) || \
        (test "x${with_glib_include}" != x && test "x${with_glib_include}" != xno) || \
         (test "x${with_glibconfig}" != x && test "x${with_glibconfig}" != xno) || \
          (test "x${with_glibconfig_include}" != x && test "x${with_glibconfig_include}" != xno); then
      AC_MSG_WARN([[glib not used, so --with-glib[-*] and --with-glibconfig[-*] are ignored]])
    fi
    GLIB_CFLAGS=
    GLIB_LIBS=
    GLIBCONFIG_CFLAGS=
  else
    GLIB_FOUND=no
    GLIBCONFIG_FOUND=no
    GLIBCONFIG_CFLAGS=
    if test "x${with_glib}" != x && test "x${with_glib}" != xyes; then
      AC_MSG_CHECKING([for glib header and library])
      if test -s "${with_glib}/include/glib-2.0/glib.h"; then
        GLIB_CFLAGS="-I${with_glib}/include/glib-2.0"
        GLIB_LIBS="-L${with_glib}/lib -lglib-2.0"
        GLIB_FOUND=yes
        AC_MSG_RESULT([$GLIB_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/glib-2.0/glib.h' under ${with_glib} given with the --with-glib option.])
      fi
    fi
    if test "x${with_glib_include}" != x; then
      AC_MSG_CHECKING([for glib headers])
      if test -s "${with_glib_include}/glib-2.0/glib.h"; then
        GLIB_CFLAGS="-I${with_glib_include}"
        GLIB_FOUND=yes
        AC_MSG_RESULT([$GLIB_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/glib-2.0/glib.h' under ${with_glib_include} given with the --with-glib-include option.])
      fi
    fi
    if test "x${with_glibconfig}" != x && test "x${with_glibconfig}" != xyes; then
      AC_MSG_CHECKING([for glibconfig header])
      if test -s "${with_glibconfig}/include/glibconfig.h"; then
        GLIBCONFIG_CFLAGS="-I${with_glibconfig}/include"
        GLIBCONFIG_FOUND=yes
        AC_MSG_RESULT([$GLIBCONFIG_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/glibconfig.h' under ${with_glibconfig} given with the --with-glibconfig option.])
      fi
    fi
    if test "x${with_glibconfig_include}" != x; then
      AC_MSG_CHECKING([for glibconfig headers])
      if test -s "${with_glibconfig_include}/glibconfig.h"; then
        GLIBCONFIG_CFLAGS="-I${with_glibconfig_include}"
        GLIBCONFIG_FOUND=yes
        AC_MSG_RESULT([$GLIBCONFIG_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/glibconfig.h' under ${with_glibconfig_include} given with the --with-glibconfig-include option.])
      fi
    fi
    if test "x$GLIB_FOUND" = xno || test "x$GLIBCONFIG_FOUND" = xno; then
      # Are the glib headers installed in the default /usr/include location?
      PKG_CHECK_MODULES([GLIB], [glib-2.0], [GLIB_FOUND=yes; GLIBCONFIG_FOUND=yes], [GLIB_FOUND=no; GLIBCONFIG_FOUND=no; break])
    fi
    if test "x$GLIB_FOUND" = xno || test "x$GLIBCONFIG_FOUND" = xno; then
      HELP_MSG_MISSING_DEPENDENCY([glib])
      AC_MSG_ERROR([Could not find glib! $HELP_MSG])
    fi
  fi
  GLIB_CFLAGS="$GLIB_CFLAGS $GLIBCONFIG_CFLAGS"
  AC_SUBST(GLIB_CFLAGS)
  AC_SUBST(GLIB_LIBS)
])