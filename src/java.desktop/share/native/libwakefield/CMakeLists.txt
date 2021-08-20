#
# Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
# Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

cmake_minimum_required(VERSION 3.16)
project(wakefield C)

set(CMAKE_C_STANDARD 11)

set(CMAKE_INSTALL_PREFIX ${CMAKE_CURRENT_SOURCE_DIR})

find_program(WAYLAND_SCANNER wayland-scanner)
if (NOT WAYLAND_SCANNER)
    message(FATAL_ERROR "wayland-scanner not found")
endif ()

add_custom_command(
        OUTPUT wakefield-server-protocol.h
        COMMAND ${WAYLAND_SCANNER} server-header ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml wakefield-server-protocol.h
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml
        VERBATIM)

add_custom_command(
        OUTPUT wakefield-server-protocol.c
        COMMAND ${WAYLAND_SCANNER} private-code ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml wakefield-server-protocol.c
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml
        VERBATIM)

find_path(
        WESTON_INCLUDES
        weston/weston.h
        /usr/include /usr/local/include)

if (NOT WESTON_INCLUDES)
    message(FATAL_ERROR "weston/weston.h not found")
endif ()

find_path(
        LIBWESTON_INCLUDES
        libweston/libweston.h
        /usr/include /usr/local/include
        PATH_SUFFIXES libweston-9)

if (NOT LIBWESTON_INCLUDES)
    message(FATAL_ERROR "libweston/libweston.h not found")
endif ()

find_path(
        PIXMAN_INCLUDES
        pixman.h
        /usr/include /usr/local/include
        PATH_SUFFIXES pixman-1)

if (NOT PIXMAN_INCLUDES)
    message(FATAL_ERROR "pixman.h not found")
endif ()

add_library(wakefield SHARED src/wakefield.c wakefield-server-protocol.c wakefield-server-protocol.h)
target_include_directories(wakefield PUBLIC
        ${WESTON_INCLUDES} ${LIBWESTON_INCLUDES} ${PIXMAN_INCLUDES}
        ${CMAKE_CURRENT_BINARY_DIR} ${CMAKE_CURRENT_SOURCE_DIR})

install(TARGETS wakefield DESTINATION .)

add_custom_command(
        OUTPUT wakefield-client-protocol.h
        COMMAND ${WAYLAND_SCANNER} client-header ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml wakefield-client-protocol.h
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml
        VERBATIM)

add_custom_command(
        OUTPUT wakefield-client-protocol.c
        COMMAND ${WAYLAND_SCANNER} code ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml wakefield-client-protocol.c
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/protocol/wakefield.xml
        VERBATIM)

add_custom_target(client ALL DEPENDS wakefield-client-protocol.h wakefield-client-protocol.c)
