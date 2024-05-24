/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2022, JetBrains s.r.o.. All rights reserved.
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

#ifndef WLBUFFERS_H
#define WLBUFFERS_H

#include <wayland-client.h>

#include "jni.h"

/**
 * An opaque type representing WayLand Surface Buffer Manager.
 */
typedef struct WLSurfaceBufferManager WLSurfaceBufferManager;

/**
 * An opaque type representing a drawing buffer obtained from
 * the WayLand Surface Buffer Manager.
 */
typedef struct WLDrawBuffer WLDrawBuffer;

/**
 * An unsigned integer representing a pixel in memory.
 * The format is defined by WLSB_DataGet() below.
 */
typedef uint32_t pixel_t;

typedef void (*BufferEventCallback)(jobject);


/**
 * Create a WayLand Surface Buffer Manager for a surface of size width x height
 * pixels with the given background 32-bit pixel value and wl_shm_format.
 *
 * At least two buffers are associated with the manager:
 * - a drawing buffer that SurfaceDataOps operate with (see WLSMSurfaceData.c) and
 * - one or more displaying buffer(s) that is essentially wl_buffer attached to wl_surface.
 *
 * Wayland displays pixels from the displaying buffer and we draw pixels to
 * the drawing buffer. The manager is responsible for timely copying from
 * the drawing to displaying buffer, synchronization, and sending
 * the appropriate notifications to Wayland.
 */
WLSurfaceBufferManager * WLSBM_Create(jint width, jint height, jint bgPixel, jint wlShmFormat,
                                      jobject surfaceDataWeakRef,
                                      BufferEventCallback frameSentCallback,
                                      BufferEventCallback frameDroppedCallback,
                                      BufferEventCallback bufferAttachedCallback);

/**
 * Free all resources allocated for the WayLand Surface Buffer Manager,
 * including associated buffers.
 */
void WLSBM_Destroy(WLSurfaceBufferManager *);

/**
 * Associate a wl_surface with the WayLand Surface Buffer Manager.
 * The "displaying" buffer will be attached to this surface when
 * the buffer is ready to be displayed.
 */
void WLSBM_SurfaceAssign(WLSurfaceBufferManager *, struct wl_surface *);

/**
 * Arrange to send the current drawing buffer to the Wayland server
 * to show on the screen.
 * If the attempt to send the buffer immediately fails (for example,
 * because the drawing buffer is still locked or there's nothing
 * new to send), arranges a re-try at the next 'frame' event
 * from Wayland.
 */
void WLSBM_SurfaceCommit(WLSurfaceBufferManager *);

/**
 * Returns the width of the buffer managed by this
 * WayLand Surface Buffer Manager.
 */
jint WLSBM_WidthGet(WLSurfaceBufferManager *);

/**
 * Returns the height of the buffer managed by this
 * WayLand Surface Buffer Manager.
 */
jint WLSBM_HeightGet(WLSurfaceBufferManager *);

/**
 * Changes the size of the buffer associated with the WayLand Surface
 * Buffer Manager.
 * The change is synchronized with drawing on the buffer,
 * so any draw operation performed after return from this function
 * will get a buffer of a new size.
 * The size of the buffer associated with the Wayland surface that
 * displays things will change later, but before the changes in drawing buffer
 * are propagated to the display buffer.
 */
void WLSBM_SizeChangeTo(WLSurfaceBufferManager *, jint width, jint height);

/**
 * Returns a buffer managed by the WayLand Surface Buffer Manager
 * that is suitable for drawing on it.
 * This operation locks the buffer such that any subsequent attempt
 * will wait (on a mutex) for the buffer to be returned back to
 * the manager with WLSBM_BufferReturn().
 */
WLDrawBuffer* WLSBM_BufferAcquireForDrawing(WLSurfaceBufferManager *);

/**
 * Releases the drawing buffer to the WayLand Surface Buffer Manager
 * so that the changed areas can be sent to Wayland for displaying.
 * Unlocks the buffer so that it can be modified again with
 * WLSBM_BufferAcquireForDrawing().
 */
void WLSBM_BufferReturn(WLSurfaceBufferManager *, WLDrawBuffer*);

/**
 * Records an area of WayLand Surface Buffer that was modified in the buffer.
 */
void WLSB_Damage(WLDrawBuffer *, jint x, jint y, jint width, jint height);

/**
 * Returns a pointer to the memory area where pixels of the WayLand
 * Surface Buffer are stored.
 * Each pixel is represented with a uint32_t and has WL_SHM_FORMAT_XRGB8888 format.
 */
pixel_t * WLSB_DataGet(WLDrawBuffer *);

#endif
