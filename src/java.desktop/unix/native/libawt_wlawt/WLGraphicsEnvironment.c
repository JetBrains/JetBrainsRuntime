/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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
#ifndef HEADLESS

#include <stdbool.h>
#include <string.h>
#include <stdlib.h>
#include <assert.h>
#include <Trace.h>

#include "JNIUtilities.h"
#include "WLToolkit.h"
#include "WLGraphicsEnvironment.h"

typedef struct WLOutputOLD {
    struct WLOutputOLD *  next;

    struct wl_output * wl_output;
    struct zxdg_output_v1 * zxdg_output;
    uint32_t id;

    // For versions of zxdg_output < 3, the compositor might send both wl_output.done and zxdg_output.done,
    // or it might not, especially if zxdg_output properties haven't changed.
    // Upon receiving the first done() in this situation, we create a wl_callback, which will be signaled by
    // the compositor when it finishes processing all pending events.
    // We will notify Java about the pending state after that callback triggers.
    // We store a reference to that callback here:
    struct wl_callback * configure_callback;

    int32_t x;
    int32_t y;
    int32_t width;
    int32_t height;
    int32_t width_logical;
    int32_t height_logical;
    int32_t width_mm;
    int32_t height_mm;

    uint32_t subpixel;
    uint32_t transform;
    uint32_t scale;

    char *   make;
    char *   model;
    char *   name;

    bool     offset_known; // whether x and y were set by xdg_output
} WLOutputOLD;

WLOutputOLD * outputList;

bool
wlToDeviceSpaceBounds(int *x, int *y, int *width, int *height)
{
    WLOutputOLD *match = NULL;

    for (WLOutputOLD *cur = outputList; cur; cur = cur->next) {
        if (cur->x == *x && cur->y == *y &&
            cur->width_logical == *width &&
            cur->height_logical == *height) {
            if (match != NULL) {
                // Multiple outputs match the same logical bounds (e.g. mirrored).
                // Cannot determine a unique scale; return false.
#ifdef DEBUG
                fprintf(stderr, "WLOutputToDeviceSpaceBounds: ambiguous match for (%d,%d,%d,%d)\n",
                        x, y, width, height);
#endif
                return false;
            }
            match = cur;
        }
    }

    if (match == NULL) {
        return false;
    }
    if (match->scale != 1) {
        *width = match->width;
        *height = match->height;
    }
    return true;
}

void
wlToCompositorSpaceCoords(int *x, int *y,
                         int boundsX, int boundsY,
                         int boundsWidth, int boundsHeight)
{
    for (WLOutputOLD *cur = outputList; cur; cur = cur->next) {
        if (cur->x == boundsX && cur->y == boundsY &&
            cur->width == boundsWidth &&
            cur->height == boundsHeight) {
            if (cur->scale != 1) {
                *x = *x / (int)cur->scale;
                *y = *y / (int)cur->scale;
            }
            return;
        }
    }
}

#endif // #ifndef HEADLESS
