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
  AC_ARG_WITH(at-spi2-atk-version, [AS_HELP_STRING([--with-at-spi2-atk-version],
        [specify version for the at-spi2-atk package])])

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
      if test "x${with_at_spi2_atk_version}" != x && "x${with_at_spi2_atk_version}" != xyes; then
        AT_SPI2_ATK_VERSION="${with_at_spi2_atk_version}"
      else
        AC_MSG_ERROR([Define at-spi2-atk package version using --with-at-spi2-atk-version if you use --with-at-spi2-atk option.])
      fi
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
      if test "x${with_at_spi2_atk_version}" != x && "x${with_at_spi2_atk_version}" != xyes; then
        AT_SPI2_ATK_VERSION="${with_at_spi2_atk_version}"
      else
        AC_MSG_ERROR([Define at-spi2-atk package version using --with-at-spi2-atk-version if you use --with-at-spi2-atk-include option.])
      fi
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
      PKG_CHECK_MODULES([AT_SPI2_ATK], [atk-bridge-2.0],
        [AT_SPI2_ATK_FOUND=yes; AT_SPI2_ATK_VERSION=$("$PKG_CONFIG" --modversion atk-bridge-2.0)],
        [AT_SPI2_ATK_FOUND=no; break]
      )
    fi
    if test "x$AT_SPI2_ATK_FOUND" = xno; then
      HELP_MSG_MISSING_DEPENDENCY([at-spi2-atk])
      AC_MSG_ERROR([Could not find at-spi2-atk! $HELP_MSG ])
    fi
  fi
  AT_SPI2_ATK_CFLAGS="$AT_SPI2_ATK_CFLAGS -DATSPI_MAJOR_VERSION=$(echo $AT_SPI2_ATK_VERSION | cut -d. -f1) -DATSPI_MINOR_VERSION=$(echo $AT_SPI2_ATK_VERSION | cut -d. -f2) -DATSPI_MICRO_VERSION=$(echo $AT_SPI2_ATK_VERSION | cut -d. -f3)"
  AC_SUBST(AT_SPI2_ATK_CFLAGS)
  AC_SUBST(AT_SPI2_ATK_LIBS)
])