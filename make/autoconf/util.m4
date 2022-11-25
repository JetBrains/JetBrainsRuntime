#
# Copyright (c) 2011, 2020, Oracle and/or its affiliates. All rights reserved.
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

m4_include([util_paths.m4])

###############################################################################
# Create a function/macro that takes a series of named arguments. The call is
# similar to AC_DEFUN, but the setup of the function looks like this:
# UTIL_DEFUN_NAMED([MYFUNC], [FOO *BAR], [$@], [
# ... do something
#   AC_MSG_NOTICE([Value of BAR is ARG_BAR])
# ])
# A star (*) in front of a named argument means that it is required and it's
# presence will be verified. To pass e.g. the first value as a normal indexed
# argument, use [m4_shift($@)] as the third argument instead of [$@]. These
# arguments are referenced in the function by their name prefixed by ARG_, e.g.
# "ARG_FOO".
#
# The generated function can be called like this:
# MYFUNC(FOO: [foo-val],
#     BAR: [
#         $ECHO hello world
#     ])
# Note that the argument value must start on the same line as the argument name.
#
# Argument 1: Name of the function to define
# Argument 2: List of legal named arguments, with a * prefix for required arguments
# Argument 3: Argument array to treat as named, typically $@
# Argument 4: The main function body
AC_DEFUN([UTIL_DEFUN_NAMED],
[
  AC_DEFUN($1, [
    m4_foreach(arg, m4_split($2), [
      m4_if(m4_bregexp(arg, [^\*]), -1,
        [
          m4_set_add(legal_named_args, arg)
        ],
        [
          m4_set_add(legal_named_args, m4_substr(arg, 1))
          m4_set_add(required_named_args, m4_substr(arg, 1))
        ]
      )
    ])

    m4_foreach([arg], [$3], [
      m4_define(arg_name, m4_substr(arg, 0, m4_bregexp(arg, [: ])))
      m4_set_contains(legal_named_args, arg_name, [],[AC_MSG_ERROR([Internal error: arg_name is not a valid named argument to [$1]. Valid arguments are 'm4_set_contents(legal_named_args, [ ])'.])])
      m4_set_remove(required_named_args, arg_name)
      m4_set_remove(legal_named_args, arg_name)
      m4_pushdef([ARG_][]arg_name, m4_substr(arg, m4_incr(m4_incr(m4_bregexp(arg, [: ])))))
      m4_set_add(defined_args, arg_name)
      m4_undefine([arg_name])
    ])
    m4_set_empty(required_named_args, [], [
      AC_MSG_ERROR([Internal error: Required named arguments are missing for [$1]. Missing arguments: 'm4_set_contents(required_named_args, [ ])'])
    ])
    m4_foreach([arg], m4_indir([m4_dquote]m4_set_listc([legal_named_args])), [
      m4_pushdef([ARG_][]arg, [])
      m4_set_add(defined_args, arg)
    ])
    m4_set_delete(legal_named_args)
    m4_set_delete(required_named_args)

    # Execute function body
    $4

    m4_foreach([arg], m4_indir([m4_dquote]m4_set_listc([defined_args])), [
      m4_popdef([ARG_][]arg)
    ])

    m4_set_delete(defined_args)
  ])
])

###############################################################################
# Check if a list of space-separated words are selected only from a list of
# space-separated legal words. Typical use is to see if a user-specified
# set of words is selected from a set of legal words.
#
# Sets the specified variable to list of non-matching (offending) words, or to
# the empty string if all words are matching the legal set.
#
# $1: result variable name
# $2: list of values to check
# $3: list of legal values
AC_DEFUN([UTIL_GET_NON_MATCHING_VALUES],
[
  # grep filter function inspired by a comment to http://stackoverflow.com/a/1617326
  # Notice that the original variant fails on SLES 10 and 11
  # Some grep versions (at least bsd) behaves strangely on the base case with
  # no legal_values, so make it explicit.
  values_to_check=`$ECHO $2 | $TR ' ' '\n'`
  legal_values=`$ECHO $3 | $TR ' ' '\n'`
  if test -z "$legal_values"; then
    $1="$2"
  else
    result=`$GREP -Fvx "$legal_values" <<< "$values_to_check" | $GREP -v '^$'`
    $1=${result//$'\n'/ }
  fi
])

###############################################################################
# Check if a list of space-separated words contains any word(s) from a list of
# space-separated illegal words. Typical use is to see if a user-specified
# set of words contains any from a set of illegal words.
#
# Sets the specified variable to list of matching illegal words, or to
# the empty string if no words are matching the illegal set.
#
# $1: result variable name
# $2: list of values to check
# $3: list of illegal values
AC_DEFUN([UTIL_GET_MATCHING_VALUES],
[
  # grep filter function inspired by a comment to http://stackoverflow.com/a/1617326
  # Notice that the original variant fails on SLES 10 and 11
  # Some grep versions (at least bsd) behaves strangely on the base case with
  # no legal_values, so make it explicit.
  values_to_check=`$ECHO $2 | $TR ' ' '\n'`
  illegal_values=`$ECHO $3 | $TR ' ' '\n'`
  if test -z "$illegal_values"; then
    $1=""
  else
    result=`$GREP -Fx "$illegal_values" <<< "$values_to_check" | $GREP -v '^$'`
    $1=${result//$'\n'/ }
  fi
])

###############################################################################
# Sort a space-separated list, and remove duplicates.
#
# Sets the specified variable to the resulting list.
#
# $1: result variable name
# $2: list of values to sort
AC_DEFUN([UTIL_SORT_LIST],
[
  values_to_sort=`$ECHO $2 | $TR ' ' '\n'`
  result=`$SORT -u <<< "$values_to_sort" | $GREP -v '^$'`
  $1=${result//$'\n'/ }
])

###############################################################################
# Test if $1 is a valid argument to $3 (often is $JAVA passed as $3)
# If so, then append $1 to $2 \
# Also set JVM_ARG_OK to true/false depending on outcome.
AC_DEFUN([UTIL_ADD_JVM_ARG_IF_OK],
[
  $ECHO "Check if jvm arg is ok: $1" >&AS_MESSAGE_LOG_FD
  $ECHO "Command: $3 $1 -version" >&AS_MESSAGE_LOG_FD
  OUTPUT=`$3 $1 $USER_BOOT_JDK_OPTIONS -version 2>&1`
  FOUND_WARN=`$ECHO "$OUTPUT" | $GREP -i warn`
  FOUND_VERSION=`$ECHO $OUTPUT | $GREP " version \""`
  if test "x$FOUND_VERSION" != x && test "x$FOUND_WARN" = x; then
    $2="[$]$2 $1"
    JVM_ARG_OK=true
  else
    $ECHO "Arg failed:" >&AS_MESSAGE_LOG_FD
    $ECHO "$OUTPUT" >&AS_MESSAGE_LOG_FD
    JVM_ARG_OK=false
  fi
])

###############################################################################
# Register a --with argument but mark it as deprecated
# $1: The name of the with argument to deprecate, not including --with-
AC_DEFUN([UTIL_DEPRECATED_ARG_WITH],
[
  AC_ARG_WITH($1, [AS_HELP_STRING([--with-$1],
      [Deprecated. Option is kept for backwards compatibility and is ignored])],
      [AC_MSG_WARN([Option --with-$1 is deprecated and will be ignored.])])
])

###############################################################################
# Register a --enable argument but mark it as deprecated
# $1: The name of the with argument to deprecate, not including --enable-
# $2: The name of the argument to deprecate, in shell variable style (i.e. with _ instead of -)
# $3: Messages to user.
AC_DEFUN([UTIL_DEPRECATED_ARG_ENABLE],
[
  AC_ARG_ENABLE($1, [AS_HELP_STRING([--enable-$1],
      [Deprecated. Option is kept for backwards compatibility and is ignored])])
  if test "x$enable_$2" != x; then
    AC_MSG_WARN([Option --enable-$1 is deprecated and will be ignored.])

    if test "x$3" != x; then
      AC_MSG_WARN([$3])
    fi

  fi
])

###############################################################################
# Register an --enable-* argument as an alias for another argument.
# $1: The name of the enable argument for the new alias, not including --enable-
# $2: The full name of the argument of which to make this an alias, including
#     --enable- or --with-.
AC_DEFUN([UTIL_ALIASED_ARG_ENABLE],
[
  AC_ARG_ENABLE($1, [AS_HELP_STRING([--enable-$1], [alias for $2])], [
    # Use m4 to strip initial -- from target ($2), convert - to _, prefix enable_
    # to new alias name, and create a shell variable assignment,
    # e.g.: enable_old_style="$enable_new_alias"
    translit(patsubst($2, --), -, _)="$[enable_]translit($1, -, _)"
  ])
])
