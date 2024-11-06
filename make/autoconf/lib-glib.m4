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
  if test "x$NEEDS_LIB_GLIB" = xfalse || test "x${with_glib}" = xno || \
      test "x${with_glib_include}" = xno; then
    if (test "x${with_glib}" != x && test "x${with_glib}" != xno) || \
        (test "x${with_glib_include}" != x && test "x${with_glib_include}" != xno); then
      AC_MSG_WARN([[glib not used, so --with-glib[-*] is ignored]])
    fi
    GLIB_CFLAGS=
    GLIB_LIBS=
  else
    GLIB_FOUND=no

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
    if test "x$GLIB_FOUND" = xno; then
      # Are the glib headers installed in the default /usr/include location?

      # FIXME: AC_CHECK_HEADERS doesn't find the header without CPPFLAGS update
      PKG_CHECK_MODULES([GLIB], [glib-2.0], CPPFLAGS="$CPPFLAGS $GLIB_CFLAGS", break)

      AC_CHECK_HEADERS([glib-2.0/glib.h],
          [ GLIB_FOUND=yes; GLIB_LIBS="-lglib-2.0" ],
          [ GLIB_FOUND=no; break ]
      )
    fi
  fi
  AC_SUBST(GLIB_CFLAGS)
  AC_SUBST(GLIB_LIBS)
])