#!/bin/bash

#
# Simple collect all possible include dirs
#

function traverseInclude() {
for file in "$1"/*
do
    if [ -d "${file}" ] ; then
        echo "${file}" >> $2
        traverseInclude "${file}" "$2"
    fi
done
}

WORKING_DIR=`pwd`
cd ..


ROOT_DIR="."
SRC_ROOT_DIR="$ROOT_DIR/src"
INCLUDE_DIRS_LIST_TMP_FILE="include.list.tmp"

traverseInclude "$SRC_ROOT_DIR/hotspot/os/posix/include" 	$INCLUDE_DIRS_LIST_TMP_FILE

traverseInclude "$SRC_ROOT_DIR/java.base/share/native" 	    $INCLUDE_DIRS_LIST_TMP_FILE
traverseInclude "$SRC_ROOT_DIR/java.desktop/share/native" 	$INCLUDE_DIRS_LIST_TMP_FILE
traverseInclude "$SRC_ROOT_DIR/java.desktop/macosx/native" 	$INCLUDE_DIRS_LIST_TMP_FILE

traverseInclude "$SRC_ROOT_DIR/../build/macosx-x86_64-server-release/support/headers/java.desktop/" $INCLUDE_DIRS_LIST_TMP_FILE

# TODO: obtain CONF from make


#
# Collect all src files by extension
#

function traverseSources() {
for file in "$1"/*
do
    if [ ! -d "${file}" ] ; then
        filename=$(basename "$file")
        ext="${filename##*.}"
    	# echo "the ext for $file is: "$ext
        if [ "$ext" = "m" ] || [ "$ext" = "c" ] || [ "$ext" = "cpp" ] || [ "$ext" = "cc" ] || [ "$ext" = "mm" ]
        then
        	echo "${file}" >> $2
        fi
    else
        traverseSources "${file}" "$2"
    fi
done
}

SOURCES_LIST_TMP_FILE="sources.list.tmp"

traverseSources "$SRC_ROOT_DIR/java.desktop/share/native" 	$SOURCES_LIST_TMP_FILE
traverseSources "$SRC_ROOT_DIR/java.desktop/macosx/native" 	$SOURCES_LIST_TMP_FILE

#
# Replace stubs in template
#

#copy
cp "$ROOT_DIR/make/clion/template/CMakeLists.txt" "$ROOT_DIR"
CMAKE_LISTS_FILE="$ROOT_DIR/CMakeLists.txt"

#replace includes
awk 'FNR==NR{s=s"\n"$0;next;} /_CMAKE_INCLUDE_DIRS_LIST_/{$0=substr(s,2);} 1' $INCLUDE_DIRS_LIST_TMP_FILE $CMAKE_LISTS_FILE > $CMAKE_LISTS_FILE.tmp
mv $CMAKE_LISTS_FILE.tmp $CMAKE_LISTS_FILE

#replace sources
awk 'FNR==NR{s=s"\n"$0;next;} /_CMAKE_SOURCE_FILES_LIST_/{$0=substr(s,2);} 1' $SOURCES_LIST_TMP_FILE $CMAKE_LISTS_FILE > $CMAKE_LISTS_FILE.tmp
mv $CMAKE_LISTS_FILE.tmp $CMAKE_LISTS_FILE

rm $INCLUDE_DIRS_LIST_TMP_FILE
rm $SOURCES_LIST_TMP_FILE
