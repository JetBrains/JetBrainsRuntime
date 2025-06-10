/*
 * Copyright (c) 2021, JetBrains s.r.o.. All rights reserved.
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

#include <wayland-client.h>
#include <wayland-cursor.h>
#include "xdg-shell.h"
#include "xdg-activation-v1.h"
#include "xdg-output-unstable-v1.h"
#include "primary-selection-unstable-v1.h"
#include "viewporter.h"
#include "jvm_md.h"
#include "jni_util.h"

#define CHECK_NULL_THROW_OOME_RETURN(env, x, msg, z)\
    do {                                        \
        if ((x) == NULL) {                      \
           JNU_ThrowOutOfMemoryError((env), (msg));\
           return (z);                          \
        }                                       \
    } while(0)                                  \

#define CHECK_NULL_THROW_IE(env, x, msg)        \
    do {                                        \
        if ((x) == NULL) {                      \
           JNU_ThrowInternalError((env), (msg));\
           return;                              \
        }                                       \
    } while(0)                                  \

struct gtk_shell1;

extern struct wl_seat *wl_seat;
extern struct wl_display *wl_display;
extern struct wl_pointer *wl_pointer; // optional, check for NULL before use
extern struct wl_compositor *wl_compositor;
extern struct wl_subcompositor *wl_subcompositor;
extern struct xdg_wm_base *xdg_wm_base;
extern struct wp_viewporter *wp_viewporter;
extern struct xdg_activation_v1 *xdg_activation_v1; // optional, check for NULL before use
#ifdef HAVE_GTK_SHELL1
extern struct gtk_shell1* gtk_shell1; // optional, check for NULL before use
#endif
extern struct wl_cursor_theme *wl_cursor_theme;
extern struct wl_data_device_manager *wl_ddm;
extern struct zwp_primary_selection_device_manager_v1 *zwp_selection_dm; // optional, check for NULL before use
extern struct zxdg_output_manager_v1 *zxdg_output_manager_v1; // optional, check for NULL before use

JNIEnv *getEnv();

int wlFlushToServer(JNIEnv* env);
struct wl_cursor_theme *getCursorTheme(int scale);

struct wl_shm_pool *CreateShmPool(size_t size, const char *name, void **data, int* poolFDPtr);
