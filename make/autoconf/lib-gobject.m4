################################################################################
# Setup gobject
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_GOBJECT],
[
  AC_ARG_WITH(gobject, [AS_HELP_STRING([--with-gobject],
      [specify prefix directory for the gobject package
      (expecting the headers under PATH/include); required for atk-wrapper to work])])
  AC_ARG_WITH(gobject-include, [AS_HELP_STRING([--with-gobject-include],
      [specify directory for the gobject include files])])

  if test "x$NEEDS_LIB_GOBJECT" = xfalse || test "x${with_gobject}" = xno || \
      test "x${with_gobject_include}" = xno; then
    if (test "x${with_gobject}" != x && test "x${with_gobject}" != xno) || \
        (test "x${with_gobject_include}" != x && test "x${with_gobject_include}" != xno); then
      AC_MSG_WARN([[gobject not used, so --with-gobject[-*] is ignored]])
    fi
    GOBJECT_CFLAGS=
    GOBJECT_LIBS=
  else
    GOBJECT_FOUND=no

    if test "x${with_gobject}" != x && test "x${with_gobject}" != xyes; then
      AC_MSG_CHECKING([for gobject header and library])
      if test -s "${with_gobject}/include/glib-2.0/gobject/gobject.h"; then
        GOBJECT_CFLAGS="-I${with_gobject}/include/glib-2.0/gobject"
        GOBJECT_LIBS="-L${with_gobject}/lib -lgobject-2.0"
        GOBJECT_FOUND=yes
        AC_MSG_RESULT([$GOBJECT_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/glib-2.0/gobject/gobject.h' under ${with_gobject} given with the --with-gobject option.])
      fi
    fi
    if test "x${with_gobject_include}" != x; then
      AC_MSG_CHECKING([for gobject headers])
      if test -s "${with_gobject_include}/glib-2.0/gobject/gobject.h"; then
        GOBJECT_CFLAGS="-I${with_gobject_include}/glib-2.0/gobject"
        GOBJECT_FOUND=yes
        AC_MSG_RESULT([$GOBJECT_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/glib-2.0/gobject/gobject.h' under ${with_gobject_include} given with the --with-gobject-include option.])
      fi
    fi
    if test "x$GOBJECT_FOUND" = xno; then
      # Are the gobject headers installed in the default /usr/include location?

      # FIXME: AC_CHECK_HEADERS doesn't find the header without CPPFLAGS update
      PKG_CHECK_MODULES([GOBJECT], [gobject-2.0], CPPFLAGS="$CPPFLAGS $GOBJECT_CFLAGS", break)

      AC_CHECK_HEADERS([glib-object.h],
          [ GOBJECT_FOUND=yes; GOBJECT_LIBS="-lgobject-2.0" ],
          [ GOBJECT_FOUND=no; break ]
      )
    fi
  fi
  AC_SUBST(GOBJECT_CFLAGS)
  AC_SUBST(GOBJECT_LIBS)
])