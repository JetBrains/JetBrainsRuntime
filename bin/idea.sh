#!/bin/sh
#
# Copyright (c) 2009, 2025, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
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

# Shell script for generating an IDEA project from a given list of modules

usage() {
      echo "Usage: $0 [-h|--help] [-q|--quiet] [-a|--absolute-paths] [-r|--root <path>] [-o|--output <path>] [-c|--conf <conf_name>] [modules...]"
      echo "    -h | --help"
      echo "    -q | --quiet
        No stdout output"
      echo "    -a | --absolute-paths
        Use absolute paths to this jdk, so that generated .idea
        project files can be moved independently of jdk sources"
      echo "    -r | --root <path>
        Project content root
        Default: $TOPLEVEL_DIR"
      echo "    -o | --output <path>
        Where .idea directory with project files will be generated
        (e.g. using '-o .' will place project files in './.idea')
        Default: same as --root"
      echo "    -c | --conf <conf_name>
        make configuration (release, slowdebug etc)"
      echo "    [modules...]
        Generate project modules for specific java modules
        (e.g. 'java.base java.desktop')
        Default: all existing modules (java.* and jdk.*)"
      exit 1
}

SCRIPT_DIR=`dirname $0`
#assume TOP is the dir from which the script has been called
TOP=`pwd`
cd $SCRIPT_DIR; SCRIPT_DIR=`pwd`
if [ "x$TOPLEVEL_DIR" = "x" ] ; then
  cd .. ; TOPLEVEL_DIR=`pwd`
fi
cd $TOP;

VERBOSE=true
ABSOLUTE_PATHS=false
CONF_ARG=
while [ $# -gt 0 ]
do
  case $1 in
    -h | --help )
      usage
      ;;

    -q | --quiet )
      VERBOSE=false
      ;;

    -a | --absolute-paths )
      ABSOLUTE_PATHS=true
      ;;

    -r | --root )
      TOPLEVEL_DIR="$2"
      shift
      ;;

    -o | --output )
      IDEA_OUTPUT="$2/.idea"
      shift
      ;;

    -c | --conf )
      CONF_ARG="CONF_NAME=$2"
      shift
      ;;

    -*)  # bad option
      usage
      ;;

     * )  # non option
      break
      ;;
  esac
  shift
done

if [ "x$IDEA_OUTPUT" = "x" ] ; then
  IDEA_OUTPUT="$TOPLEVEL_DIR/.idea"
fi

mkdir -p $IDEA_OUTPUT || exit 1
cd "$TOP" ; cd $TOPLEVEL_DIR; TOPLEVEL_DIR=`pwd`
cd "$TOP" ; cd $IDEA_OUTPUT; IDEA_OUTPUT=`pwd`
cd ..; IDEA_OUTPUT_PARENT=`pwd`
cd "$SCRIPT_DIR/.." ; OPENJDK_DIR=`pwd`

IDEA_MAKE="$OPENJDK_DIR/make/ide/idea/jdk"
IDEA_TEMPLATE="$IDEA_MAKE/template"

cp -r "$IDEA_TEMPLATE"/* "$IDEA_OUTPUT"

#override template
if [ -d "$TEMPLATES_OVERRIDE" ] ; then
    for file in `ls -p "$TEMPLATES_OVERRIDE" | grep -v /`; do
        cp "$TEMPLATES_OVERRIDE"/$file "$IDEA_OUTPUT"/
    done
fi

if [ "$VERBOSE" = true ] ; then
  echo "Will generate IDEA project files in \"$IDEA_OUTPUT\" for project \"$TOPLEVEL_DIR\""
fi

cd $TOP ; make idea-gen-config ALLOW=TOPLEVEL_DIR,IDEA_OUTPUT_PARENT,IDEA_OUTPUT,MODULES TOPLEVEL_DIR="$TOPLEVEL_DIR" \
    IDEA_OUTPUT_PARENT="$IDEA_OUTPUT_PARENT" IDEA_OUTPUT="$IDEA_OUTPUT" MODULES="$*" $CONF_ARG || exit 1
cd $SCRIPT_DIR

. $IDEA_OUTPUT/env.cfg

# Expect MODULES, MODULE_NAMES, RELATIVE_PROJECT_DIR, RELATIVE_BUILD_DIR to be set
if [ "xMODULES" = "x" ] ; then
  echo "FATAL: MODULES is empty" >&2; exit 1
fi

if [ "x$MODULE_NAMES" = "x" ] ; then
  echo "FATAL: MODULE_NAMES is empty" >&2; exit 1
fi

if [ "x$RELATIVE_PROJECT_DIR" = "x" ] ; then
  echo "FATAL: RELATIVE_PROJECT_DIR is empty" >&2; exit 1
fi

if [ "x$RELATIVE_BUILD_DIR" = "x" ] ; then
  echo "FATAL: RELATIVE_BUILD_DIR is empty" >&2; exit 1
fi

if [ -d "$TOPLEVEL_DIR/.hg" ] ; then
    VCS_TYPE="hg4idea"
fi

# Git worktrees use a '.git' file rather than directory, so test both.
if [ -d "$TOPLEVEL_DIR/.git" -o -f "$TOPLEVEL_DIR/.git" ] ; then
    VCS_TYPE="Git"
fi

if [ "$ABSOLUTE_PATHS" = true ] ; then
  if [ "x$PATHTOOL" != "x" ]; then
    PROJECT_DIR="`$PATHTOOL -am $OPENJDK_DIR`"
    TOPLEVEL_PROJECT_DIR="`$PATHTOOL -am $TOPLEVEL_DIR`"
  else
    PROJECT_DIR="$OPENJDK_DIR"
    TOPLEVEL_PROJECT_DIR="$TOPLEVEL_DIR"
  fi
  MODULE_DIR="$PROJECT_DIR"
  TOPLEVEL_MODULE_DIR="$TOPLEVEL_PROJECT_DIR"
  cd "$IDEA_OUTPUT_PARENT" && cd "$RELATIVE_BUILD_DIR" && BUILD_DIR="`pwd`"
  CLION_SCRIPT_TOPDIR="$OPENJDK_DIR"
  CLION_PROJECT_DIR="$PROJECT_DIR"
else
  if [ "$RELATIVE_PROJECT_DIR" = "." ] ; then
    PROJECT_DIR=""
  else
    PROJECT_DIR="/$RELATIVE_PROJECT_DIR"
  fi
  if [ "$RELATIVE_TOPLEVEL_PROJECT_DIR" = "." ] ; then
    TOPLEVEL_PROJECT_DIR=""
  else
    TOPLEVEL_PROJECT_DIR="/$RELATIVE_TOPLEVEL_PROJECT_DIR"
  fi
  MODULE_DIR="\$MODULE_DIR\$$PROJECT_DIR"
  PROJECT_DIR="\$PROJECT_DIR\$$PROJECT_DIR"
  TOPLEVEL_MODULE_DIR="\$MODULE_DIR\$$TOPLEVEL_PROJECT_DIR"
  TOPLEVEL_PROJECT_DIR="\$PROJECT_DIR\$$TOPLEVEL_PROJECT_DIR"
  BUILD_DIR="\$PROJECT_DIR\$/$RELATIVE_BUILD_DIR"
  CLION_SCRIPT_TOPDIR="$CLION_RELATIVE_PROJECT_DIR"
  CLION_PROJECT_DIR="\$PROJECT_DIR\$/$CLION_SCRIPT_TOPDIR"
fi
if [ "$VERBOSE" = true ] ; then
  echo "Project root: $PROJECT_DIR"
  echo "Generating IDEA project files..."
fi

### Replace template variables

NUM_REPLACEMENTS=0

replace_template_file() {
    for i in $(seq 1 $NUM_REPLACEMENTS); do
      eval "sed \"s|\${FROM${i}}|\${TO${i}}|g\" $1 > $1.tmp"
      mv $1.tmp $1
    done
}

replace_template_dir() {
    for f in `find $1 -type f` ; do
        replace_template_file $f
    done
}

add_replacement() {
    NUM_REPLACEMENTS=`expr $NUM_REPLACEMENTS + 1`
    eval FROM$NUM_REPLACEMENTS='$1'
    eval TO$NUM_REPLACEMENTS='$2'
}

add_replacement "###PATHTOOL###" "$PATHTOOL"
add_replacement "###CLION_SCRIPT_TOPDIR###" "$CLION_SCRIPT_TOPDIR"
add_replacement "###CLION_PROJECT_DIR###" "$CLION_PROJECT_DIR"
add_replacement "###PROJECT_DIR###" "$PROJECT_DIR"
add_replacement "###MODULE_DIR###" "$MODULE_DIR"
add_replacement "###TOPLEVEL_PROJECT_DIR###" "$TOPLEVEL_PROJECT_DIR"
add_replacement "###TOPLEVEL_MODULE_DIR###" "$TOPLEVEL_MODULE_DIR"
add_replacement "###MODULE_NAMES###" "$MODULE_NAMES"
add_replacement "###VCS_TYPE###" "$VCS_TYPE"
add_replacement "###BUILD_DIR###" "$BUILD_DIR"
add_replacement "###RELATIVE_BUILD_DIR###" "$RELATIVE_BUILD_DIR"
if [ "x$PATHTOOL" != "x" ]; then
  add_replacement "###BASH_RUNNER_PREFIX###" "\$PROJECT_DIR\$/.idea/bash.bat"
else
  add_replacement "###BASH_RUNNER_PREFIX###" ""
fi
if [ "x$PATHTOOL" != "x" ]; then
    if [ "x$JT_HOME" = "x" ]; then
      add_replacement "###JTREG_HOME###" ""
    else
      add_replacement "###JTREG_HOME###" "`$PATHTOOL -am $JT_HOME`"
    fi
else
    add_replacement "###JTREG_HOME###" "$JT_HOME"
fi

MODULE_IMLS=""
TEST_MODULE_DEPENDENCIES=""
for module in $MODULE_NAMES; do
    MODULE_IMLS="$MODULE_IMLS<module fileurl=\"file://\$PROJECT_DIR$/.idea/$module.iml\" filepath=\"\$PROJECT_DIR$/.idea/$module.iml\" /> "
    TEST_MODULE_DEPENDENCIES="$TEST_MODULE_DEPENDENCIES<orderEntry type=\"module\" module-name=\"$module\" scope=\"TEST\" /> "
done
add_replacement "###MODULE_IMLS###" "$MODULE_IMLS"
add_replacement "###TEST_MODULE_DEPENDENCIES###" "$TEST_MODULE_DEPENDENCIES"

replace_template_dir "$IDEA_OUTPUT"

### Generate module project files

if [ "$VERBOSE" = true ] ; then
    echo "Generating project modules:"
  fi
(
DEFAULT_IFS="$IFS"
IFS='#'
for value in $MODULES; do
  (
  eval "$value"
  if [ "$VERBOSE" = true ] ; then
    echo "    $module"
  fi
  MAIN_SOURCE_DIRS=""
  CONTENT_ROOTS=""
  IFS=' '
  for dir in $moduleSrcDirs; do
    case $dir in
      "src/"*) MAIN_SOURCE_DIRS="$MAIN_SOURCE_DIRS <sourceFolder url=\"file://$MODULE_DIR/$dir\" isTestSource=\"false\" />" ;;
      *"/support/gensrc/$module") ;; # Exclude generated sources to avoid module-info conflicts, see https://youtrack.jetbrains.com/issue/IDEA-185108
      *) CONTENT_ROOTS="$CONTENT_ROOTS <content url=\"file://$MODULE_DIR/$dir\">\
      <sourceFolder url=\"file://$MODULE_DIR/$dir\" isTestSource=\"false\" generated=\"true\" /></content>" ;;
    esac
  done
  if [ "x$MAIN_SOURCE_DIRS" != "x" ] ; then
    CONTENT_ROOTS="<content url=\"file://$MODULE_DIR/src/$module\">$MAIN_SOURCE_DIRS</content>$CONTENT_ROOTS"
  fi
  add_replacement "###MODULE_CONTENT_ROOTS###" "$CONTENT_ROOTS"
  DEPENDENCIES=""
  for dep in $moduleDependencies; do
    case $MODULE_NAMES in # Exclude skipped modules from dependencies
      *"$dep"*) DEPENDENCIES="$DEPENDENCIES<orderEntry type=\"module\" module-name=\"$dep\" /> "
    esac
  done
  add_replacement "###DEPENDENCIES###" "$DEPENDENCIES"
  cp "$IDEA_OUTPUT/module.iml" "$IDEA_OUTPUT/$module.iml"
  IFS="$DEFAULT_IFS"
  replace_template_file "$IDEA_OUTPUT/$module.iml"
  )
done
)
rm "$IDEA_OUTPUT/module.iml"

### Create shell script runner for Windows

if [ "x$PATHTOOL" != "x" ]; then
  echo "@echo off" > "$IDEA_OUTPUT/bash.bat"
  if [ "x$WSL_DISTRO_NAME" != "x" ] ; then
    echo "wsl -d $WSL_DISTRO_NAME --cd \"%cd%\" -e %*" >> "$IDEA_OUTPUT/bash.bat"
  else
    echo "$WINENV_ROOT\bin\bash.exe -l -c \"cd %CD:\=/%/ && %*\"" >> "$IDEA_OUTPUT/bash.bat"
  fi
fi



if [ "$VERBOSE" = true ] ; then
  IDEA_PROJECT_DIR="`dirname $IDEA_OUTPUT`"
  if [ "x$PATHTOOL" != "x" ]; then
    IDEA_PROJECT_DIR="`$PATHTOOL -am $IDEA_PROJECT_DIR`"
  fi
  echo "
Now you can open \"$IDEA_PROJECT_DIR\" as IDEA project
You can also run 'bash \"$IDEA_OUTPUT/jdk-clion/update-project.sh\"' to generate Clion project"
fi