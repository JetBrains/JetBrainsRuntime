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

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/sysinfo.h>
#include <pthread.h>
#include <assert.h>

#include "Trace.h"

#include "awt.h"

#include "WLBuffers.h"
#include "WLToolkit.h"

#ifndef HEADLESS

typedef struct WLSurfaceBuffer WLSurfaceBuffer;

static void
SurfaceBufferNotifyReleased(WLSurfaceBufferManager * manager, struct wl_buffer * wl_buffer);

static void
ScheduleFrameCallback(WLSurfaceBufferManager * manager);

static void
CancelFrameCallback(WLSurfaceBufferManager * manager);

static void
CopyDrawBufferToShowBuffer(WLSurfaceBufferManager * manager);

static void
SendShowBufferToWayland(WLSurfaceBufferManager * manager);

static inline void
ReportFatalError(const char* file, int line, const char *msg)
{
    fprintf(stderr, "Fatal error at %s:%d: %s\n", file, line, msg);
    fflush(stderr);
    assert(0);
}

static void
AssertDrawLockIsHeld(WLSurfaceBufferManager* manager, const char * file, int line);

#define WL_FATAL_ERROR(msg) ReportFatalError(__FILE__, __LINE__, msg)
#define ASSERT_DRAW_LOCK_IS_HELD(manager) AssertDrawLockIsHeld(manager, __FILE__, __LINE__)
#define ASSERT_SHOW_LOCK_IS_HELD(manager) AssertShowLockIsHeld(manager, __FILE__, __LINE__)

#define MUTEX_LOCK(m)   if (pthread_mutex_lock(&(m)))   { WL_FATAL_ERROR("Failed to lock mutex"); }
#define MUTEX_UNLOCK(m) if (pthread_mutex_unlock(&(m))) { WL_FATAL_ERROR("Failed to unlock mutex"); }

#define CLAMP(val, min, max) ((val) < (min) ? (min) : ((val) > (max) ? (max) : (val)))

/**
 * The maximum number of buffers that can be simultaneously in use by Wayland.
 * When a new frame is ready to be sent to Wayland and the number of buffers
 * already sent plus this new buffer exceeds MAX_BUFFERS_IN_USE, that frame is
 * skipped. We will wait for a buffer to be released.
 * NB: neither the buffer for drawing nor the next buffer reserved to be sent to
 *     Wayland count towards MAX_BUFFERS_IN_USE.
 * Cannot be less than two because some compositors will not release the buffer
 * given to them until a new one has been attached. See the description of
 * the wl_buffer::release event in the Wayland documentation.
 * Larger values may make interactive resizing smoother.
 */
const int MAX_BUFFERS_IN_USE = 2;

static bool traceEnabled;    // set the J2D_STATS env var to enable

/**
 * Represents one rectangular area linked into a list.
 *
 * Such a list represents portions of a buffer that have been
 * modified (damaged) in some way.
 */
typedef struct DamageList {
    jint x, y;
    jint width, height;
    struct DamageList *next;
} DamageList;

static DamageList*
DamageList_Add(DamageList* list, jint x, jint y, jint width, jint height)
{
    DamageList * l = list;
    DamageList * p = NULL;
    while (l) {
        if (x >= l->x && y >= l->y
                && x + width <= l->x + l->width
                && y + height <= l->y + l->height) {
            // No need to add an area completely covered by another one.
            return list;
        } else if (x <= l->x && y <= l->y
                && x + width >= l->x + l->width
                && y + height >= l->y + l->height) {
            // The new element will cover this area, no need to keep
            // a separate damage element for it.
            DamageList * next = l->next;
            if (p != NULL) {
                p->next = next;
            } else {
                list = next;
            }
            free(l);
            l = next;
        } else {
            p = l;
            l = l->next;
        }
    }

    DamageList *item = malloc(sizeof(DamageList));
    if (!item) {
        JNU_ThrowOutOfMemoryError(getEnv(), "Failed to allocate Wayland buffer damage list");
    } else {
        item->x = x;
        item->y = y;
        item->width = width;
        item->height = height;
        item->next = list;
    }
    return item;
}

static DamageList*
DamageList_AddList(DamageList* list, DamageList* add)
{
    if (add != NULL) {
        assert(list != add);

        for (DamageList *l = add; l != NULL; l = l->next) {
            list = DamageList_Add(list, l->x, l->y, l->width, l->height);
        }
    }
    return list;
}

static void
DamageList_SendAll(DamageList* list, struct wl_surface* wlSurface)
{
    while (list) {
        wl_surface_damage_buffer(wlSurface,
                                 list->x, list->y,
                                 list->width, list->height);
        list = list->next;
    }
}

static void
DamageList_FreeAll(DamageList* list)
{
    while (list) {
        DamageList * next = list->next;
        free(list);
        list = next;
    }
}

// Identifies a frame that is being drawn or displayed on the screen.
// Will stay unique for approx 2 years of uptime at 60fps.
typedef uint32_t frame_id_t;

/**
 * Contains data needed to maintain a wl_buffer instance.
 *
 * This buffer is usually attached to a wl_surface.
 * Its dimensions determine the dimensions of the surface.
 */
typedef struct WLSurfaceBuffer {
    struct WLSurfaceBuffer * next;      /// links buffers in a list
    struct wl_shm_pool *     wlPool;    /// the pool this buffer was allocated from
    struct wl_buffer *       wlBuffer;  /// the Wayland buffer itself
    int                      fd;        /// the file descriptor of the mmap-ed file
    jint                     width;     /// buffer's width
    jint                     height;    /// buffer's height
    size_t                   bytesAllocated; /// the size of the memory segment pointed to by data
    pixel_t *                data;      /// points to a memory segment shared with Wayland
    DamageList *             damageList;/// Accumulated damage relative to the current show buffer
} WLSurfaceBuffer;

/**
 * Represents the buffer that will be sent to the Wayland server next.
 */
typedef struct WLShowBuffer {
    WLSurfaceBuffer * wlSurfaceBuffer; /// The next buffer to be sent to Wayland
    DamageList *      damageList;      /// Areas of the buffer that need to be re-drawn by Wayland
    frame_id_t        frameID;         /// ID of the frame currently sent to Wayland
} WLShowBuffer;

/**
 * Represents a buffer to draw in.
 *
 * The underlying pixels array is pointed to by the data field.
 * The changes made by drawing are accumulated in the damageList field.
 */
struct WLDrawBuffer {
    WLSurfaceBufferManager * manager;
    jint                     width;
    jint                     height;
    jboolean                 resizePending;  /// The next access to the buffer requires DrawBufferResize()
    size_t                   bytesAllocated; /// the size of the memory segment pointed to by data
    pixel_t *                data;       /// Actual pixels of the buffer
    DamageList *             damageList; /// Areas of the buffer that may have been altered
    frame_id_t               frameID;    /// ID of the frame being drawn
};

/**
 * Contains data necessary to manage multiple backing buffers for one wl_surface.
 *
 * There's one buffer that will be sent to Wayland next: bufferForShow. When it is
 * ready to be sent to Wayland, it is added to the buffersInUse list and a new one
 * is put in its place so that bufferForShow is always available.
 * If the number of buffers in use is >= MAX_BUFFERS_IN_USE, no new buffer is sent
 * to Wayland until some buffers have been released by Wayland. This effectively
 * skips some of the frames.
 *
 * There's one buffer that can be drawn upon: bufferForDraw. When we're done drawing,
 * pixels from that buffer are copied over to bufferForShow.
 *
 * The size of bufferForShow is determined by the width and height fields.
 */
struct WLSurfaceBufferManager {
    struct wl_surface * wlSurface;         // only accessed under showLock
    bool                sendBufferASAP;    // only accessed under showLock
    int                 bgPixel;           // the pixel value to be used to clear new buffers
    int                 format;            // one of enum wl_shm_format

    struct wl_callback* wl_frame_callback; // only accessed under showLock

    pthread_mutex_t     showLock;

    /**
     * The "show" buffer that is next to be sent to Wayland for showing on the screen.
     * When sent to Wayland, its WLSurfaceBuffer is added to the buffersInUse list
     * and a fresh one created or re-used from the buffersFree list so that
     * this buffer is available at all times.
     * When the "draw" buffer (bufferForDraw) size is changed, this one is
     * immediately invalidated along with all those from the buffersFree list.
     */
    WLShowBuffer        bufferForShow; // only accessed under showLock

    /// The list of buffers that can be re-used as bufferForShow.wlSurfaceBuffer.
    WLSurfaceBuffer *   buffersFree;   // only accessed under showLock

    /// The list of buffers sent to Wayland and not yet released; when released,
    /// they may be added to the buffersFree list.
    /// Does not exceed MAX_BUFFERS_IN_USE elements.
    WLSurfaceBuffer *   buffersInUse;  // only accessed under showLock

    pthread_mutex_t     drawLock;

    /**
     * The "draw" buffer where new painting is going on, which will be then
     * copied over to the "show" buffer and finally sent to Wayland for showing
     * on the screen.
     */
    WLDrawBuffer        bufferForDraw; // only accessed under drawLock

    jobject              surfaceDataWeakRef;
    BufferEventCallback frameSentCallback;
    BufferEventCallback frameDroppedCallback;
    BufferEventCallback bufferAttachedCallback;
};

static inline void
AssertDrawLockIsHeld(WLSurfaceBufferManager* manager, const char * file, int line)
{
    // The drawLock is recursive, so can't effectively check if it is locked
    // with trylock. Maybe add a manual lock count or current owner?
}

static inline void
AssertShowLockIsHeld(WLSurfaceBufferManager* manager, const char * file, int line)
{
#ifdef DEBUG
    if (pthread_mutex_trylock(&manager->showLock) == 0) {
        fprintf(stderr, "showLock not acquired at %s:%d\n", file, line);
        fflush(stderr);
        assert(0);
    }
#endif
}

static jlong
GetJavaTimeNanos(void) {
    jlong result = 0;
    if (traceEnabled) {
        struct timespec tp;
        const jlong NANOSECS_PER_SEC = 1000000000L;
        clock_gettime(CLOCK_MONOTONIC, &tp);
        result = (jlong)(tp.tv_sec) * NANOSECS_PER_SEC + (jlong)(tp.tv_nsec);
    }
    return result;
}

static inline void
WLBufferTrace(WLSurfaceBufferManager* manager, const char *fmt, ...)
{
    if (traceEnabled) {
        va_list args;
        va_start(args, fmt);
        jlong t = GetJavaTimeNanos();
        fprintf(stderr, "[%07dms] [%04x] ", t / 1000000, ((unsigned long)manager->wlSurface & 0xfffful));
        vfprintf(stderr, fmt, args);
        fprintf(stderr, "; frames [^%03d, *%03d]\n",
                manager->bufferForShow.frameID,
                manager->bufferForDraw.frameID);
        fflush(stderr);
        va_end(args);
    }
}

static inline size_t
SurfaceBufferSizeInBytes(WLSurfaceBuffer * buffer)
{
    assert (buffer);

    const jint stride = buffer->width * (jint)sizeof(pixel_t);
    return stride * buffer->height;
}

/**
 * Returns the number of bytes in the "draw" buffer.
 *
 * This can differ from the size of the "show" buffer at
 * certain points in time until that "show" buffer gets
 * released to us by Wayland and gets readjusted.
 */
static inline size_t
DrawBufferSizeInBytes(WLSurfaceBufferManager * manager)
{
    const jint stride = manager->bufferForDraw.width * (jint)sizeof(pixel_t);
    return stride * manager->bufferForDraw.height;
}

/**
 * Returns the number of pixels in the "draw" buffer.
 */
static inline jint
DrawBufferSizeInPixels(WLSurfaceBufferManager * manager)
{
    return manager->bufferForDraw.width * manager->bufferForDraw.height;
}

static void
wl_buffer_release(void * data, struct wl_buffer * wl_buffer)
{
    /* Sent by the compositor when it's no longer using this buffer */
    WLSurfaceBufferManager * manager = (WLSurfaceBufferManager *) data;
    WLBufferTrace(manager, "wl_buffer_release");
    SurfaceBufferNotifyReleased(manager, wl_buffer);
}

static const struct wl_buffer_listener wl_buffer_listener = {
        .release = wl_buffer_release,
};

static void
SurfaceBufferDestroy(WLSurfaceBuffer * buffer)
{
    assert(buffer);

    if (buffer->fd != 0) {
        // NB: the server (Wayland) will hold this memory for a bit longer, so it's
        // OK to unmap now without waiting for the "release" event for the buffer
        // from Wayland.
        close(buffer->fd);
    }

    if (buffer->data != NULL) {
        munmap(buffer->data, buffer->bytesAllocated);
    }

    if (buffer->wlPool != NULL) {
        wl_shm_pool_destroy(buffer->wlPool);
    }

    if (buffer->wlBuffer != NULL) {
        // "Destroying the wl_buffer after wl_buffer.release does not change
        //  the surface contents" (source: wayland.xml)
        wl_buffer_destroy(buffer->wlBuffer);
    }

    DamageList_FreeAll(buffer->damageList);
    free(buffer);
}

static WLSurfaceBuffer *
SurfaceBufferCreate(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);
    WLBufferTrace(manager, "SurfaceBufferCreate");

    WLSurfaceBuffer * buffer = calloc(1, sizeof(WLSurfaceBuffer));
    if (!buffer) return NULL;

    buffer->width = manager->bufferForDraw.width;
    buffer->height = manager->bufferForDraw.height;
    buffer->bytesAllocated = SurfaceBufferSizeInBytes(buffer);
    buffer->wlPool = CreateShmPool(buffer->bytesAllocated, "jwlshm", (void**)&buffer->data, &buffer->fd);
    if (! buffer->wlPool) {
        SurfaceBufferDestroy(buffer);
        return NULL;
    }

    const int32_t stride = (int32_t) (buffer->width * sizeof(pixel_t));
    buffer->wlBuffer = wl_shm_pool_create_buffer(buffer->wlPool, 0,
                                                 buffer->width,
                                                 buffer->height,
                                                 stride,
                                                 manager->format);
    if (! buffer->wlBuffer) {
        SurfaceBufferDestroy(buffer);
        return NULL;
    }

    wl_buffer_add_listener(buffer->wlBuffer,
                           &wl_buffer_listener,
                           manager);

    buffer->damageList = DamageList_Add(NULL, 0, 0, buffer->width, buffer->height);
    if (buffer->damageList == NULL) {
        SurfaceBufferDestroy(buffer);
        return NULL;
    }

    return buffer;
}

static bool
SurfaceBufferResize(WLSurfaceBufferManager * manager, WLSurfaceBuffer* buffer)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    assert(buffer != NULL);
    assert(buffer->wlBuffer != NULL);

    jint newWidth = manager->bufferForDraw.width;
    jint newHeight = manager->bufferForDraw.height;

    wl_buffer_destroy(buffer->wlBuffer);
    buffer->wlBuffer = NULL;
    DamageList_FreeAll(buffer->damageList);
    buffer->damageList = NULL;

    buffer->width = newWidth;
    buffer->height = newHeight;

    size_t requiredSize = SurfaceBufferSizeInBytes(buffer);
    if (buffer->bytesAllocated < requiredSize) {
        if (ftruncate(buffer->fd, requiredSize)) {
            return false;
        }

        void * newData = mremap(buffer->data, buffer->bytesAllocated, requiredSize, MREMAP_MAYMOVE);
        if (newData == MAP_FAILED) {
            return false;
        }

        buffer->data = newData;
        wl_shm_pool_resize(buffer->wlPool, requiredSize);
        buffer->bytesAllocated = requiredSize;
    }

    const int32_t stride = (int32_t) (buffer->width * sizeof(pixel_t));
    buffer->wlBuffer = wl_shm_pool_create_buffer(buffer->wlPool, 0,
                                                 buffer->width,
                                                 buffer->height,
                                                 stride,
                                                 manager->format);
    if (buffer->wlBuffer == NULL) {
        return false;
    }

    buffer->damageList = DamageList_Add(NULL, 0, 0, newWidth, newHeight);
    if (buffer->damageList == NULL) {
        return  false;
    }

    wl_buffer_add_listener(buffer->wlBuffer,
                           &wl_buffer_listener,
                           manager);
    return true;
}

static void
SurfaceBufferNotifyReleased(WLSurfaceBufferManager * manager, struct wl_buffer * wl_buffer)
{
    assert(manager);

    if (traceEnabled) {
        int used = 0;
        int free = 0;
        WLSurfaceBuffer * cur = manager->buffersInUse;
        while (cur) {
            used++;
            cur = cur->next;
        }
        cur = manager->buffersFree;
        while (cur) {
            free++;
            cur = cur->next;
        }
        WLBufferTrace(manager, "SurfaceBufferNotifyReleased (%d in use, %d free)", used, free);
    }

    MUTEX_LOCK(manager->showLock);

    WLSurfaceBuffer * cur = manager->buffersInUse;
    WLSurfaceBuffer * prev = NULL;
    while (cur) {
        if (cur->wlBuffer == wl_buffer) {
            // Delete from the "in-use" list
            if (prev) {
                prev->next = cur->next;
            } else {
                manager->buffersInUse = cur->next;
            }

            // Add to the "free" list
            cur->next = manager->buffersFree;
            manager->buffersFree = cur;
            break;
        }

        prev = cur;
        cur = cur->next;
    }

    MUTEX_UNLOCK(manager->showLock);
}

static void
ShowBufferChooseFromFree(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    assert(manager->buffersFree != NULL);

    manager->bufferForShow.wlSurfaceBuffer = manager->buffersFree;
    manager->buffersFree = manager->buffersFree->next;
    manager->bufferForShow.wlSurfaceBuffer->next = NULL;
}

static bool
ShowBufferNeedsResize(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    jint newWidth = manager->bufferForDraw.width;
    jint newHeight = manager->bufferForDraw.height;
    return newWidth != manager->bufferForShow.wlSurfaceBuffer->width || newHeight != manager->bufferForShow.wlSurfaceBuffer->height;
}

static bool
ShowBufferResize(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    if (!SurfaceBufferResize(manager, manager->bufferForShow.wlSurfaceBuffer)) {
        SurfaceBufferDestroy(manager->bufferForShow.wlSurfaceBuffer);
        manager->bufferForShow.wlSurfaceBuffer = NULL;
        JNU_ThrowOutOfMemoryError(getEnv(), "Failed to allocate Wayland surface buffer");
        return false;
    }

    return true;
}

static bool
ShowBufferCreate(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    assert(manager->bufferForShow.wlSurfaceBuffer == NULL);

    WLSurfaceBuffer* buffer = SurfaceBufferCreate(manager);
    if (buffer == NULL) {
        JNU_ThrowOutOfMemoryError(getEnv(), "Failed to allocate Wayland surface buffer");
        return false;
    }

    manager->bufferForShow.wlSurfaceBuffer = buffer;
    return true;
}

static bool
ShowBufferIsAvailable(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    // Skip sending the next frame if the number of buffers that
    // had been sent to Wayland for displaying earlier is too large.
    // Clearly the server can't support our frame rate in that case.
    int used = 0;
    WLSurfaceBuffer * cur = manager->buffersInUse;
    while (cur) {
        used++;
        cur = cur->next;
    }
    WLBufferTrace(manager, "ShowBufferIsAvailable: %d/%d in use", used, MAX_BUFFERS_IN_USE);

    // NB: account for one extra buffer about to be sent to Wayland and added to the used list
    bool canSendMoreBuffers = used < MAX_BUFFERS_IN_USE;
    if (canSendMoreBuffers) {
        if (manager->bufferForShow.wlSurfaceBuffer == NULL) {
            if (manager->buffersFree != NULL) {
                ShowBufferChooseFromFree(manager);
            } else {
                if (!ShowBufferCreate(manager)) {
                    return false; // OOM
                }
            }
        }

        if (ShowBufferNeedsResize(manager)) {
            if (!ShowBufferResize(manager)) {
                return false; // failed to resize, likely due to OOM
            }
        }
    } else {
        if (manager->frameDroppedCallback != NULL) {
            manager->frameDroppedCallback(manager->surfaceDataWeakRef);
        }
    }

    return canSendMoreBuffers;
}

static bool
TryCopyDrawBufferToShowBuffer(WLSurfaceBufferManager * manager, bool sendNow)
{
    MUTEX_LOCK(manager->drawLock); // So that the size doesn't change while we copy
    sendNow = sendNow && ShowBufferIsAvailable(manager);
    if (sendNow) {
        CopyDrawBufferToShowBuffer(manager);
    }
    MUTEX_UNLOCK(manager->drawLock);
    return sendNow;
}

static void
TrySendShowBufferToWayland(WLSurfaceBufferManager * manager, bool sendNow)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    WLBufferTrace(manager, "TrySendShowBufferToWayland(%s)", sendNow ? "now" : "later");

    if (TryCopyDrawBufferToShowBuffer(manager, sendNow)) {
        SendShowBufferToWayland(manager);
    } else {
        ScheduleFrameCallback(manager);
    }

    WLBufferTrace(manager, "wl_surface_commit");
    // Need to commit either the damage done to the surface or the re-scheduled callback.
    wl_surface_commit(manager->wlSurface);
}

static void
wl_frame_callback_done(void * data,
                       struct wl_callback * wl_callback,
                       uint32_t callback_data)
{
    WLSurfaceBufferManager * manager = (WLSurfaceBufferManager *) data;

    WLBufferTrace(manager, "wl_frame_callback_done");

    MUTEX_LOCK(manager->showLock);

    assert(manager->wl_frame_callback == wl_callback);
    CancelFrameCallback(manager);

    if (manager->wlSurface) {
        const bool hasSomethingToSend = (manager->bufferForDraw.damageList != NULL);
        if (hasSomethingToSend) {
            TrySendShowBufferToWayland(manager, true);
        }
        // In absence of damage, wait for another WLSBM_SurfaceCommit() instead of waiting
        // for another frame; the latter may never bring anything different in case of
        // a static picture, so we'll be cycling frames for nothing.
    }

    MUTEX_UNLOCK(manager->showLock);
}

static const struct wl_callback_listener wl_frame_callback_listener = {
        .done = wl_frame_callback_done
};

static bool
IsFrameCallbackScheduled(WLSurfaceBufferManager * manager)
{
    return manager->wl_frame_callback != NULL;
}

static void
ScheduleFrameCallback(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    assert(manager->wlSurface);

    if (!IsFrameCallbackScheduled(manager)) {
        manager->wl_frame_callback = wl_surface_frame(manager->wlSurface);
        wl_callback_add_listener(manager->wl_frame_callback, &wl_frame_callback_listener, manager);
    }
}

static void
CancelFrameCallback(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    if (IsFrameCallbackScheduled(manager)) {
        wl_callback_destroy(manager->wl_frame_callback);
        manager->wl_frame_callback = NULL;
    }
}

/**
 * Attaches the current show buffer to the Wayland surface, notifying Wayland
 * of all the damaged areas in that buffer.
 */
static void
SendShowBufferToWayland(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    assert(manager->wlSurface);

    jlong startTime = GetJavaTimeNanos();

    WLSurfaceBuffer * buffer = manager->bufferForShow.wlSurfaceBuffer;
    if (buffer == NULL) {
        // There should've been an OOME thrown already
        return;
    }

    // We'll choose a free buffer or create a new one for the next frame
    // when the time comes (see ShowBufferIsAvailable()).
    manager->bufferForShow.wlSurfaceBuffer = NULL;

    // wl_buffer_listener will release bufferForShow when Wayland's done with it
    wl_surface_attach(manager->wlSurface, buffer->wlBuffer, 0, 0);
    //NB: do not specify scale for the buffer; we scale with wp_viewporter
    manager->bufferAttachedCallback(manager->surfaceDataWeakRef);

    // Better wait for the frame event so as not to overwhelm Wayland with
    // frequent surface updates that it cannot deliver to the screen anyway.
    manager->sendBufferASAP = false;

    DamageList_SendAll(manager->bufferForShow.damageList, manager->wlSurface);
    DamageList_FreeAll(manager->bufferForShow.damageList);
    manager->bufferForShow.damageList = NULL;

    buffer->next = manager->buffersInUse;
    manager->buffersInUse = buffer;

    manager->bufferForShow.frameID = manager->bufferForDraw.frameID;
    manager->bufferForDraw.frameID++;

    jlong endTime = GetJavaTimeNanos();
    WLBufferTrace(manager, "SendShowBufferToWayland (%lldns)", endTime - startTime);
    if (manager->frameSentCallback != NULL) {
        manager->frameSentCallback(manager->surfaceDataWeakRef);
    }
}

static void
CopyDamagedArea(WLSurfaceBufferManager * manager, jint x, jint y, jint width, jint height)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    assert(manager->bufferForShow.wlSurfaceBuffer != NULL);
    assert(manager->bufferForShow.wlSurfaceBuffer->data != NULL);
    assert(manager->bufferForDraw.data != NULL);
    assert(manager->bufferForDraw.width == manager->bufferForShow.wlSurfaceBuffer->width);
    assert(manager->bufferForDraw.height == manager->bufferForShow.wlSurfaceBuffer->height);
    assert(manager->bufferForShow.wlSurfaceBuffer->bytesAllocated >= DrawBufferSizeInBytes(manager));

    jint bufferWidth = manager->bufferForDraw.width;
    jint bufferHeight = manager->bufferForDraw.height;

    // Clamp the damaged area to the size of the destination in order not to crash in case of
    // an error on the client side that "damaged" an area larger than our buffer.
    x = CLAMP(x, 0, bufferWidth - 1);
    y = CLAMP(y, 0, bufferHeight - 1);
    width = CLAMP(width, 0, bufferWidth - x);
    height = CLAMP(height, 0, bufferHeight - y);

    pixel_t * dest = manager->bufferForShow.wlSurfaceBuffer->data;
    pixel_t * src  = manager->bufferForDraw.data;

    for (jint i = y; i < height + y; i++) {
        pixel_t * dest_row = &dest[i * bufferWidth];
        pixel_t * src_row  = &src [i * bufferWidth];
        for (jint j = x; j < width + x; j++) {
            dest_row[j] = src_row[j];
        }
    }
}

/**
 * Copies the contents of the drawing surface to the buffer associated
 * with the Wayland surface for displaying, the "show" buffer.
 *
 * Clears the list of damaged areas from the drawing buffer and
 * moves that list to the "show" buffer so that Wayland can get
 * notified of what has changed in the buffer.
 *
 * Updates the damaged areas in all the existing free and in-use buffers.
 */
static void
CopyDrawBufferToShowBuffer(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    if (manager->bufferForShow.wlSurfaceBuffer == NULL
        || manager->bufferForDraw.data == NULL
        || manager->bufferForDraw.resizePending) {
        return;
    }

    assert(manager->wlSurface != NULL);
    assert(manager->bufferForShow.damageList == NULL);

    jlong startTime = GetJavaTimeNanos();

    // All the existing buffers will now differ even more from the new "show" buffer.
    // Need to add to their damaged areas.
    for (WLSurfaceBuffer* buffer = manager->buffersFree; buffer != NULL; buffer = buffer->next) {
        buffer->damageList = DamageList_AddList(buffer->damageList, manager->bufferForDraw.damageList);
    }

    for (WLSurfaceBuffer* buffer = manager->buffersInUse; buffer != NULL; buffer = buffer->next) {
        buffer->damageList = DamageList_AddList(buffer->damageList, manager->bufferForDraw.damageList);
    }

    // Merge the damage list with the new damage from the draw buffer; this is better than
    // copying damage from two lists because this way we might avoid copying the same area twice.
    manager->bufferForShow.wlSurfaceBuffer->damageList
            = DamageList_AddList(manager->bufferForShow.wlSurfaceBuffer->damageList,
                                 manager->bufferForDraw.damageList);

    int count = 0;
    for (DamageList* l = manager->bufferForShow.wlSurfaceBuffer->damageList; l != NULL; l = l->next) {
        CopyDamagedArea(manager, l->x, l->y, l->width, l->height);
        count++;
    }

    // This buffer is now identical to what's on the screen, so clear the difference list:
    DamageList_FreeAll(manager->bufferForShow.wlSurfaceBuffer->damageList);
    manager->bufferForShow.wlSurfaceBuffer->damageList = NULL;

    // The list of damage to notify Wayland about:
    manager->bufferForShow.damageList = manager->bufferForDraw.damageList;
    manager->bufferForDraw.damageList = NULL;

    jlong endTime = GetJavaTimeNanos();
    WLBufferTrace(manager, "CopyDrawBufferToShowBuffer: copied %d area(s) in %lldns", count, endTime - startTime);
}

static void
DrawBufferDestroy(WLSurfaceBufferManager * manager)
{
    free(manager->bufferForDraw.data);
    manager->bufferForDraw.data = NULL;
    DamageList_FreeAll(manager->bufferForDraw.damageList);
    manager->bufferForDraw.damageList = NULL;
}

static void
DrawBufferResize(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    DamageList_FreeAll(manager->bufferForDraw.damageList);
    manager->bufferForDraw.damageList = NULL;

    manager->bufferForDraw.resizePending = false;
    manager->bufferForDraw.frameID++;

    size_t requiredSize = DrawBufferSizeInBytes(manager);
    if (manager->bufferForDraw.bytesAllocated < requiredSize) {
        free(manager->bufferForDraw.data);
        manager->bufferForDraw.data = NULL;

        void * data = malloc(requiredSize);
        if (data == NULL) {
            JNU_ThrowOutOfMemoryError(getEnv(), "Failed to allocate Wayland surface buffer");
            return;
        }

        manager->bufferForDraw.data = data;
        manager->bufferForDraw.bytesAllocated = requiredSize;
    }

    for (jint i = 0; i < DrawBufferSizeInPixels(manager); ++i) {
        manager->bufferForDraw.data[i] = manager->bgPixel;
    }
}

static void ResetBuffers(WLSurfaceBufferManager * manager) {
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    // Make sure the draw buffer is not copied from and re-set before drawn upon again
    MUTEX_LOCK(manager->drawLock);
    manager->bufferForDraw.resizePending = true;
    MUTEX_UNLOCK(manager->drawLock);

    // All the damage refers to a surface that doesn't exist anymore; clean the lists
    // in case these buffers will be re-used for a new surface
    for (WLSurfaceBuffer* buffer = manager->buffersFree; buffer != NULL; buffer = buffer->next) {
        DamageList_FreeAll(buffer->damageList);
        buffer->damageList = NULL;
    }

    for (WLSurfaceBuffer* buffer = manager->buffersInUse; buffer != NULL; buffer = buffer->next) {
        DamageList_FreeAll(buffer->damageList);
        buffer->damageList = NULL;
    }
}

static bool
HaveEnoughMemoryForWindow(jint width, jint height)
{
    if (width == 0 || height == 0) return true;

    struct sysinfo si;
    sysinfo(&si);
    unsigned long freeMemBytes = (si.freeram + si.freeswap) * si.mem_unit;
    unsigned long maxBuffers = freeMemBytes / width / height / sizeof(pixel_t);

    // Prevent the computer from becoming totally unresponsive some time down
    // the line when the buffers start to allocate and start throwing inevitable OOMEs.
    return maxBuffers >= 2;
}

WLSurfaceBufferManager *
WLSBM_Create(jint width,
             jint height,
             jint bgPixel,
             jint wlShmFormat,
             jobject surfaceDataWeakRef,
             BufferEventCallback frameSentCallback,
             BufferEventCallback frameDroppedCallback,
             BufferEventCallback bufferAttachedCallback)
{
    assert (surfaceDataWeakRef != NULL);
    assert (bufferAttachedCallback != NULL);

    traceEnabled = getenv("J2D_STATS");

    if (!HaveEnoughMemoryForWindow(width, height)) {
       return NULL;
    }

    WLSurfaceBufferManager * manager = calloc(1, sizeof(WLSurfaceBufferManager));
    if (!manager) {
        return NULL;
    }

    manager->bufferForDraw.width = width;
    manager->bufferForDraw.height = height;
    manager->bgPixel = bgPixel;
    manager->format = wlShmFormat;
    manager->surfaceDataWeakRef = surfaceDataWeakRef;
    manager->frameSentCallback = frameSentCallback;
    manager->frameDroppedCallback = frameDroppedCallback;
    manager->bufferAttachedCallback = bufferAttachedCallback;

    pthread_mutex_init(&manager->showLock, NULL);

    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    // Recursive mutex is required because the same "draw" buffer can be
    // both read from and written to (when scrolling, for instance).
    // So it needs to be able to be "locked" twice: once for writing and
    // once for reading.
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&manager->drawLock, &attr);

    manager->bufferForDraw.manager = manager;
    manager->bufferForDraw.resizePending = true;

    J2dTrace(J2D_TRACE_INFO, "WLSBM_Create: created %p for %dx%d px\n", manager, width, height);
    return manager;
}

void
WLSBM_SurfaceAssign(WLSurfaceBufferManager * manager, struct wl_surface* wl_surface)
{
    J2dTrace(J2D_TRACE_INFO, "WLSBM_SurfaceAssign: assigned surface %p to manger %p\n", wl_surface, manager);
    WLBufferTrace(manager, "WLSBM_SurfaceAssign(%p)", wl_surface);

    MUTEX_LOCK(manager->showLock);
    if (manager->wlSurface == NULL || wl_surface == NULL) {
        manager->wlSurface = wl_surface;
        // The "frame" callback depends on the surface; when changing the surface,
        // cancel any associated pending callbacks:
        CancelFrameCallback(manager);
        if (wl_surface != NULL) {
            // If there's already something drawn in the buffer, let's send it right now,
            // at a minimum in order to associate the surface with a buffer and make it appear
            // on the screen. Normally this would happen in WLSBM_SurfaceCommit(),
            // but the Java side may have already drawn and committed everything it wanted and
            // will not commit anything new for a while, in which case the surface
            // may never get associated with a buffer and the window will never appear.
            if (manager->bufferForDraw.damageList != NULL) {
                WLBufferTrace(manager, "WLSBM_SurfaceAssign: surface has damage, will try to send it now");
                TrySendShowBufferToWayland(manager, true);
            } else {
                WLBufferTrace(manager, "WLSBM_SurfaceAssign: no damage, will wait for WLSBM_SurfaceCommit()");
                // The next commit must associate the surface with a buffer, so set the flag:
                manager->sendBufferASAP = true;
            }
        } else {
            ResetBuffers(manager);
        }
    } else {
        assert(manager->wlSurface == wl_surface);
    }
    MUTEX_UNLOCK(manager->showLock);
}

void
WLSBM_Destroy(WLSurfaceBufferManager * manager)
{
    J2dTrace(J2D_TRACE_INFO, "WLSBM_Destroy: manger %p\n", manager);

    JNIEnv* env = getEnv();
    (*env)->DeleteWeakGlobalRef(env, manager->surfaceDataWeakRef);

    // NB: must never be called in parallel with the Wayland event handlers
    // because their callbacks retain a pointer to this manager.
    MUTEX_LOCK(manager->showLock);
    MUTEX_LOCK(manager->drawLock);

    CancelFrameCallback(manager);
    DrawBufferDestroy(manager);

    if (manager->bufferForShow.wlSurfaceBuffer) {
        SurfaceBufferDestroy(manager->bufferForShow.wlSurfaceBuffer);
    }

    while (manager->buffersFree) {
        WLSurfaceBuffer * next = manager->buffersFree->next;
        SurfaceBufferDestroy(manager->buffersFree);
        manager->buffersFree = next;
    }

    while (manager->buffersInUse) {
        WLSurfaceBuffer * next = manager->buffersInUse->next;
        SurfaceBufferDestroy(manager->buffersInUse);
        manager->buffersInUse = next;
    }

    MUTEX_UNLOCK(manager->drawLock);
    MUTEX_UNLOCK(manager->showLock);

    pthread_mutex_destroy(&manager->showLock);
    pthread_mutex_destroy(&manager->drawLock);
    memset(manager, 0, sizeof(*manager));
    free(manager);
}

jint
WLSBM_WidthGet(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);
    return manager->bufferForDraw.width;
}

jint
WLSBM_HeightGet(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);
    return manager->bufferForDraw.height;
}

WLDrawBuffer *
WLSBM_BufferAcquireForDrawing(WLSurfaceBufferManager * manager)
{
    WLBufferTrace(manager, "WLSBM_BufferAcquireForDrawing(%d)", manager->bufferForDraw.frameID);
    MUTEX_LOCK(manager->drawLock); // unlocked in WLSBM_BufferReturn()
    if (manager->bufferForDraw.resizePending) {
        WLBufferTrace(manager, "WLSBM_BufferAcquireForDrawing - creating a new draw buffer because the size has changed");
        DrawBufferResize(manager);
    }
    return &manager->bufferForDraw;
}

void
WLSBM_BufferReturn(WLSurfaceBufferManager * manager, WLDrawBuffer * buffer)
{
    if (&manager->bufferForDraw == buffer) {
        MUTEX_UNLOCK(buffer->manager->drawLock); // locked in WLSBM_BufferAcquireForDrawing()
        WLBufferTrace(manager, "WLSBM_BufferReturn(%d)", manager->bufferForDraw.frameID);
    } else {
        WL_FATAL_ERROR("WLSBM_BufferReturn() called with an unidentified buffer");
    }
}

void
WLSBM_SurfaceCommit(WLSurfaceBufferManager * manager)
{
    MUTEX_LOCK(manager->showLock);

    const bool frameCallbackScheduled = IsFrameCallbackScheduled(manager);
    WLBufferTrace(manager, "WLSBM_SurfaceCommit (%x, %s)",
                  manager->wlSurface,
                  frameCallbackScheduled ? "wait for frame" : "now");

    if (manager->wlSurface && !frameCallbackScheduled) {
        // Don't always send the frame immediately so as not to overwhelm Wayland
        TrySendShowBufferToWayland(manager, manager->sendBufferASAP);
    }
    MUTEX_UNLOCK(manager->showLock);
}

void
WLSB_Damage(WLDrawBuffer * buffer, jint x, jint y, jint width, jint height)
{
    assert(&buffer->manager->bufferForDraw == buffer); // only one buffer currently supported
    ASSERT_DRAW_LOCK_IS_HELD(buffer->manager);
    assert(x >= 0);
    assert(y >= 0);
    assert(x + width <= buffer->manager->bufferForDraw.width);
    assert(y + height <= buffer->manager->bufferForDraw.height);

    buffer->damageList = DamageList_Add(buffer->damageList, x, y, width, height);
    WLBufferTrace(buffer->manager, "WLSB_Damage (at %d, %d %dx%d)", x, y, width, height);
}

pixel_t *
WLSB_DataGet(WLDrawBuffer * buffer)
{
    ASSERT_DRAW_LOCK_IS_HELD(buffer->manager);
    return buffer->data;
}

void
WLSBM_SizeChangeTo(WLSurfaceBufferManager * manager, jint width, jint height)
{
    if (!HaveEnoughMemoryForWindow(width, height)) {
        JNU_ThrowOutOfMemoryError(getEnv(), "Wayland surface buffer too large");
        return;
    }

    MUTEX_LOCK(manager->showLock);
    MUTEX_LOCK(manager->drawLock);
    if (manager->bufferForDraw.width != width || manager->bufferForDraw.height != height) {
        manager->bufferForDraw.width  = width;
        manager->bufferForDraw.height = height;
        manager->bufferForDraw.resizePending = true;

        // Send the buffer at the nearest commit as we want to make the change in size
        // visible to the user.
        manager->sendBufferASAP = true;

        // Need to wait for WLSBM_SurfaceCommit() with the new content for
        // the buffer size, so there's no need for the frame event until then.
        CancelFrameCallback(manager);

        WLBufferTrace(manager, "WLSBM_SizeChangeTo %dx%d", width, height);
    }

    MUTEX_UNLOCK(manager->drawLock);
    MUTEX_UNLOCK(manager->showLock);
}

#endif
