/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#ifndef SPLASHSCREEN_CONFIG_H
#define SPLASHSCREEN_CONFIG_H

#include <stdbool.h>

typedef struct Buffer {
    void *data;
    int size;
    struct wl_buffer *wl_buffer;
    bool available;
} Buffer;

typedef struct wayland_state {
    struct wl_display *wl_display;
    struct wl_registry *wl_registry;
    struct wl_shm *wl_shm;
    struct wl_compositor *wl_compositor;
    struct wl_subcompositor *wl_subcompositor;
    struct wl_output *wl_output;
    struct wl_seat *wl_seat;
    struct wl_pointer *pointer;
    struct xdg_wm_base *xdg_wm_base;
    struct wl_surface *wl_surface;
    struct wl_surface *wl_subsurfaces_surface;
    struct wl_subsurface *wl_subsurfaces_subsurface;
    struct xdg_surface *xdg_surface;
    struct xdg_toplevel *xdg_toplevel;
    struct wl_cursor_theme *cursor_theme;
    struct wl_cursor *default_cursor;
    struct wl_surface *cursor_surface;
} wayland_state;

// Actually the following Rect machinery is unused since we don't use shapes
typedef int RECT_T;

#define RECT_EQ_X(r1,r2)        ((r1) == (r2))
#define RECT_SET(r,xx,yy,ww,hh) ;
#define RECT_INC_HEIGHT(r)      ;

#endif
