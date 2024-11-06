################################################################################
# Setup at-spi2-atk
################################################################################
AC_DEFUN_ONCE([LIB_SETUP_AT_SPI2_ATK],
[
  AC_ARG_WITH(at-spi2-atk, [AS_HELP_STRING([--with-at-spi2-atk],
      [specify prefix directory for the at-spi2-atk package
      (expecting the headers under PATH/include); required for atk-wrapper to work])])
  AC_ARG_WITH(at-spi2-atk-include, [AS_HELP_STRING([--with-at-spi2-atk-include],
      [specify directory for the at-spi2-atk include files])])

  if test "x$NEEDS_LIB_AT_SPI2_ATK" = xfalse || test "x${with_at_spi2_atk}" = xno || \
      test "x${with_at_spi2_atk_include}" = xno; then
    if (test "x${with_at_spi2_atk}" != x && test "x${with_at_spi2_atk}" != xno) || \
        (test "x${with_at_spi2_atk_include}" != x && test "x${with_at_spi2_atk_include}" != xno); then
      AC_MSG_WARN([[at-spi2-atk not used, so --with-at-spi2-atk[-*] is ignored]])
    fi
    AT_SPI2_ATK_CFLAGS=
    AT_SPI2_ATK_LIBS=
  else
    AT_SPI2_ATK_FOUND=no
    if test "x${with_at_spi2_atk}" != x && test "x${with_at_spi2_atk}" != xyes; then
      AC_MSG_CHECKING([for at-spi2-atk header and library])
      if test -s "${with_at_spi2_atk}/include/at-spi2-atk/2.0/atk-bridge.h"; then
        AT_SPI2_ATK_CFLAGS="-I${with_at_spi2_atk}/include/at-spi2-atk/2.0"
        AT_SPI2_ATK_LIBS="-L${with_at_spi2_atk}/lib -latk-bridge-2.0"
        AT_SPI2_ATK_FOUND=yes
        AC_MSG_RESULT([$AT_SPI2_ATK_FOUND])
      else
        AC_MSG_ERROR([Can't find '/include/at-spi2-atk/2.0/atk-bridge.h' under ${with_at_spi2_atk} given with the --with-at-spi2-atk option.])
      fi
    fi
    if test "x${with_at_spi2_atk_include}" != x; then
      AC_MSG_CHECKING([for at-spi2-atk headers])
      if test -s "${with_at_spi2_atk_include}/at-spi2-atk/2.0/atk-bridge.h"; then
        AT_SPI2_ATK_CFLAGS="-I${with_at_spi2_atk_include}/at-spi2-atk/2.0"
        AT_SPI2_ATK_FOUND=yes
        AC_MSG_RESULT([$AT_SPI2_ATK_FOUND])
      else
        AC_MSG_ERROR([Can't find 'include/at-spi2-atk-2.0/atk-bridge.h' under ${with_at_spi2_atk_include} given with the --with-at-spi2-atk-include option.])
      fi
    fi
    if test "x$AT_SPI2_ATK_FOUND" = xno; then
      # Are the at-spi2-atk headers installed in the default /usr/include location?

      # FIXME: AC_CHECK_HEADERS doesn't find the header without CPPFLAGS update
      PKG_CHECK_MODULES([AT_SPI2_ATK], [atk-bridge-2.0], CPPFLAGS="$CPPFLAGS $AT_SPI2_ATK_CFLAGS", break)

      AC_CHECK_HEADERS([at-spi2-atk/2.0/atk-bridge.h],
          [ AT_SPI2_ATK_FOUND=yes; AT_SPI2_ATK_LIBS="-latk-bridge-2.0" ],
          [ AT_SPI2_ATK_FOUND=no; break ]
      )
    fi
  fi
  AC_SUBST(AT_SPI2_ATK_CFLAGS)
  AC_SUBST(AT_SPI2_ATK_LIBS)
])