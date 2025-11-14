################################################################################
# Setup atk
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_ATK],
[
  AC_ARG_WITH(atk, [AS_HELP_STRING([--with-atk],
      [specify prefix directory for the atk package
      (expecting the headers under PATH/include); required for atk-wrapper to work])])
  AC_ARG_WITH(atk-include, [AS_HELP_STRING([--with-atk-include],
      [specify directory for the atk include files])])
  if test "x$NEEDS_LIB_ATK" = xfalse || test "x${with_atk}" = xno || \
      test "x${with_atk_include}" = xno; then
    if (test "x${with_atk}" != x && test "x${with_atk}" != xno) || \
        (test "x${with_atk_include}" != x && test "x${with_atk_include}" != xno); then
      AC_MSG_WARN([[atk not used, so --with-atk[-*] is ignored]])
    fi
    ATK_CFLAGS=
    ATK_LIBS=
  else
    ATK_FOUND=no
    if test "x${with_atk}" != x && test "x${with_atk}" != xyes; then
      AC_MSG_CHECKING([for atk header and library])
      if test -s "${with_atk}/include/atk-1.0/atk/atk.h"; then
        ATK_CFLAGS="-I${with_atk}/include/atk-1.0"
        ATK_LIBS="-L${with_atk}/lib -latk-1.0"
        ATK_FOUND=yes
        AC_MSG_RESULT([$ATK_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/atk-1.0/atk/atk.h' under ${with_atk} given with the --with-atk option.])
      fi
    fi
    if test "x${with_atk_include}" != x; then
      AC_MSG_CHECKING([for atk headers])
      if test -s "${with_atk_include}/atk-1.0/atk/atk.h"; then
        ATK_CFLAGS="-I${with_atk_include}/atk-1.0"
        ATK_FOUND=yes
        AC_MSG_RESULT([$ATK_FOUND])
      else
        AC_MSG_ERROR([Can't find 'include/atk-1.0/atk/atk.h' under ${with_atk_include} given with the --with-atk-include option.])
      fi
    fi
    if test "x$ATK_FOUND" = xno; then
      # Are the atk headers installed in the default /usr/include location?
      PKG_CHECK_MODULES([ATK], [atk], [ATK_FOUND=yes;], [ATK_FOUND=no; break])
    fi
    if test "x$ATK_FOUND" = xno; then
      HELP_MSG_MISSING_DEPENDENCY([atk])
      AC_MSG_ERROR([Could not find atk! $HELP_MSG ])
    fi
  fi
  AC_SUBST(ATK_CFLAGS)
  AC_SUBST(ATK_LIBS)
])