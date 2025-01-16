################################################################################
# Setup glibconfig
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_GLIBCONFIG],
[
  AC_ARG_WITH(glibconfig, [AS_HELP_STRING([--with-glibconfig],
      [specify prefix directory for the glibconfig package
      (expecting the headers under PATH/include); required for atk-wrapper to work])])
  AC_ARG_WITH(glibconfig-include, [AS_HELP_STRING([--with-glibconfig-include],
      [specify directory for the glibconfig include files])])
  if test "x$NEEDS_LIB_GLIBCONFIG" = xfalse || test "x${with_glibconfig}" = xno || \
      test "x${with_glibconfig_include}" = xno; then
    if (test "x${with_glibconfig}" != x && test "x${with_glibconfig}" != xno) || \
        (test "x${with_glibconfig_include}" != x && test "x${with_glibconfig_include}" != xno); then
      AC_MSG_WARN([[glibconfig not used, so --with-glibconfig[-*] is ignored]])
    fi
  else
    GLIBCONFIG_FOUND=no
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
    if test "x$GLIBCONFIG_FOUND" = xno; then
      # Are the glibconfig header installed in the default /usr/lib/glib-2.0/include location?
      PKG_CHECK_MODULES([GLIBCONFIG], [glib-2.0],
        [GLIBCONFIG_FOUND=yes], [GLIBCONFIG_FOUND=no; break]
      )
    fi
    if test "x$GLIBCONFIG_FOUND" = xno; then
      HELP_MSG_MISSING_DEPENDENCY([glibconfig])
      AC_MSG_ERROR([Could not find glibconfig! $HELP_MSG ])
    else
      ATK_WRAPPER_CFLAGS="$ATK_WRAPPER_CFLAGS $GLIBCONFIG_CFLAGS"
      AC_SUBST(ATK_WRAPPER_CFLAGS)
    fi
  fi
])