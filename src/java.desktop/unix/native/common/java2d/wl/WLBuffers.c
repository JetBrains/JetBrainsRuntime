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
#include <pthread.h>
#include <assert.h>

#include "Trace.h"

#include "awt.h"

#include "WLBuffers.h"
#include "WLToolkit.h"

#ifndef HEADLESS

typedef struct WLSurfaceBuffer WLSurfaceBuffer;

static WLSurfaceBuffer *
SurfaceBufferCreate(WLSurfaceBufferManager * manager);

static void
SurfaceBufferNotifyReleased(WLSurfaceBufferManager * manager, struct wl_buffer * wl_buffer);

static bool
ShowBufferIsAvailable(WLSurfaceBufferManager * manager);

static void
ShowBufferInvalidateForNewSize(WLSurfaceBufferManager * manager);

static void
SurfaceBufferDestroy(WLSurfaceBuffer * buffer);

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
 */
const int MAX_BUFFERS_IN_USE = 2;

static bool traceEnabled;    // set the J2D_STATS env var to enable
static bool traceFPSEnabled; // set the J2D_FPS env var to enable

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
    item->x = x;
    item->y = y;
    item->width = width;
    item->height = height;
    item->next = list;
    return item;
}

static DamageList*
DamageList_AddList(DamageList* list, DamageList* add)
{
    assert(list != add);

    for (DamageList* l = add; l != NULL; l = l->next) {
        list = DamageList_Add(list, l->x, l->y, l->width, l->height);
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
    pixel_t *                data;      /// points to a memory segment shared with Wayland
    jint                     width;     /// buffer's width
    jint                     height;    /// buffer's height
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
    bool                isBufferAttached;  // is there a buffer attached to the surface?
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

    /// The scale of wl_surface (see Wayland docs for more info on that).
    jint                scale;         // only accessed under showLock

    pthread_mutex_t     drawLock;

    /**
     * The "draw" buffer where new painting is going on, which will be then
     * copied over to the "show" buffer and finally sent to Wayland for showing
     * on the screen.
     */
    WLDrawBuffer        bufferForDraw; // only accessed under drawLock
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
    if (traceEnabled || traceFPSEnabled) {
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

static void
WLBufferTraceFrame(WLSurfaceBufferManager* manager)
{
    if (traceFPSEnabled) {
        static jlong lastFrameTime = 0;
        static int frameCount = 0;

        jlong curTime = GetJavaTimeNanos();
        frameCount++;
        if (curTime - lastFrameTime > 1000000000L) {
            fprintf(stderr, "FPS: %d\n", frameCount);
            fflush(stderr);
            lastFrameTime = curTime;
            frameCount = 0;
        }
    }
}

static inline size_t
SurfaceBufferSizeInBytes(WLSurfaceBuffer * buffer)
{
    const jint stride = buffer->width * (jint)sizeof(pixel_t);
    return stride * buffer->height;
}

/**
 * Returns the number of bytes in the "draw" buffer.
 *
 * This can differ from the size of the "display" buffer at
 * certain points in time until that "display" buffer gets
 * released to us by Wayland and readjusts itself.
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

    // NB: the server (Wayland) will hold this memory for a bit longer, so it's
    // OK to unmap now without waiting for the "release" event for the buffer
    // from Wayland.
    const size_t size = SurfaceBufferSizeInBytes(buffer);
    munmap(buffer->data, size);
    wl_shm_pool_destroy(buffer->wlPool);

    // "Destroying the wl_buffer after wl_buffer.release does not change
    //  the surface contents" (source: wayland.xml)
    wl_buffer_destroy(buffer->wlBuffer);

    DamageList_FreeAll(buffer->damageList);
    free(buffer);
}

static WLSurfaceBuffer *
SurfaceBufferCreate(WLSurfaceBufferManager * manager)
{
    WLBufferTrace(manager, "SurfaceBufferCreate");

    WLSurfaceBuffer * buffer = calloc(1, sizeof(WLSurfaceBuffer));
    if (!buffer) return NULL;

    MUTEX_LOCK(manager->drawLock);
    buffer->width = manager->bufferForDraw.width;
    buffer->height = manager->bufferForDraw.height;
    MUTEX_UNLOCK(manager->drawLock);

    buffer->damageList = DamageList_Add(NULL, 0, 0, buffer->width, buffer->height);

    const size_t size = SurfaceBufferSizeInBytes(buffer);
    buffer->wlPool = CreateShmPool(size, "jwlshm", (void**)&buffer->data);
    if (! buffer->wlPool) {
        free(buffer);
        return NULL;
    }

    const int32_t stride = (int32_t) (buffer->width * sizeof(pixel_t));
    buffer->wlBuffer = wl_shm_pool_create_buffer(buffer->wlPool, 0,
                                                 buffer->width,
                                                 buffer->height,
                                                 stride,
                                                 manager->format);
    wl_buffer_add_listener(buffer->wlBuffer,
                           &wl_buffer_listener,
                           manager);

    return buffer;
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

            // Maybe add to the "free" list, but only if the buffer is still usable
            const bool curBufferIsUseful =
                       cur->width  == manager->bufferForDraw.width
                    && cur->height == manager->bufferForDraw.height;
            if (curBufferIsUseful) {
                cur->next = manager->buffersFree;
                manager->buffersFree = cur;
            } else {
                SurfaceBufferDestroy(cur);
            }

            break;
        }

        prev = cur;
        cur = cur->next;
    }

    MUTEX_UNLOCK(manager->showLock);
}

static bool
ShowBufferIsAvailable(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    assert(manager->bufferForShow.wlSurfaceBuffer);

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
    return used < MAX_BUFFERS_IN_USE;
}

static void
ShowBufferCreate(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    manager->bufferForShow.wlSurfaceBuffer = SurfaceBufferCreate(manager);
}

/**
 * Makes sure that there's a fresh "show" buffer of suitable size available
 * that can be sent to Wayland. Its content (actual pixels) may be garbage.
 */
static void
ShowBufferPrepareFreshOne(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    manager->bufferForShow.wlSurfaceBuffer = NULL;

    // Re-use one of the free buffers or make a new one
    if (manager->buffersFree) {
        assert(manager->bufferForDraw.width == manager->buffersFree->width);
        assert(manager->bufferForDraw.height == manager->buffersFree->height);

        manager->bufferForShow.wlSurfaceBuffer = manager->buffersFree;
        manager->buffersFree = manager->buffersFree->next;
        manager->bufferForShow.wlSurfaceBuffer->next = NULL;
    } else {
        ShowBufferCreate(manager);
    }
}

static void
TrySendShowBufferToWayland(WLSurfaceBufferManager * manager, bool sendNow)
{
    WLBufferTrace(manager, "TrySendShowBufferToWayland(%s)", sendNow ? "now" : "later");

    sendNow = sendNow && ShowBufferIsAvailable(manager);
    if (sendNow) {
        CopyDrawBufferToShowBuffer(manager);
        SendShowBufferToWayland(manager);
    } else {
        ScheduleFrameCallback(manager);
    }

    WLBufferTrace(manager, "wl_surface_commit");
    // Need to commit either the damage done to the surface or the re-scheduled callback.
    wl_surface_commit(manager->wlSurface);
}

static void
ShowBufferInvalidateForNewSize(WLSurfaceBufferManager * manager)
{
    MUTEX_LOCK(manager->showLock);

    WLSurfaceBuffer * buffer = manager->bufferForShow.wlSurfaceBuffer;
    if (buffer != NULL) {
        assert(buffer->next == NULL);
        SurfaceBufferDestroy(buffer);
        manager->bufferForShow.wlSurfaceBuffer = NULL;
        // Even though technically we didn't detach the buffer from the surface,
        // we need to attach a new, resized one as soon as possible. If we wait
        // for the next frame event to do that, Mutter may not remember
        // the latest size of the window.
        manager->isBufferAttached = false;
    }

    while (manager->buffersFree) {
        WLSurfaceBuffer * next = manager->buffersFree->next;
        SurfaceBufferDestroy(manager->buffersFree);
        manager->buffersFree = next;
    }

    // NB: the buffers that are currently in use will be destroyed
    // as soon as they are released (see wl_buffer_release()).

    ShowBufferCreate(manager);

    // Need to wait for WLSBM_SurfaceCommit() with the new content for
    // the buffer we have just created, so there's no need for the
    // frame event until then.
    CancelFrameCallback(manager);

    MUTEX_UNLOCK(manager->showLock);
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

static void
ScheduleFrameCallback(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    assert(manager->wlSurface);
    assert(manager->isBufferAttached); // or else wl_callback_add_listener() has no effect

    if (!manager->wl_frame_callback) {
        manager->wl_frame_callback = wl_surface_frame(manager->wlSurface);
        wl_callback_add_listener(manager->wl_frame_callback, &wl_frame_callback_listener, manager);
    }
}

static void
CancelFrameCallback(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    if (manager->wl_frame_callback) {
        wl_callback_destroy(manager->wl_frame_callback);
        manager->wl_frame_callback = NULL;
    }
}

/**
 * Attaches the current show buffer to the Wayland surface, notifying Wayland
 * of all the damaged areas in that buffer.
 * Prepares a fresh buffer for the next frame to show.
 */
static void
SendShowBufferToWayland(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    assert(manager->wlSurface);

    jlong startTime = GetJavaTimeNanos();

    WLSurfaceBuffer * buffer = manager->bufferForShow.wlSurfaceBuffer;
    assert(buffer);

    ShowBufferPrepareFreshOne(manager);

    // wl_buffer_listener will release bufferForShow when Wayland's done with it
    wl_surface_attach(manager->wlSurface, buffer->wlBuffer, 0, 0);
    wl_surface_set_buffer_scale(manager->wlSurface, manager->scale);

    // Wayland will not issue frame callbacks before a buffer is attached to the surface.
    // So we need to take note of the fact of attaching.
    manager->isBufferAttached = true;

    DamageList_SendAll(manager->bufferForShow.damageList, manager->wlSurface);
    DamageList_FreeAll(manager->bufferForShow.damageList);
    manager->bufferForShow.damageList = NULL;

    buffer->next = manager->buffersInUse;
    manager->buffersInUse = buffer;

    manager->bufferForShow.frameID = manager->bufferForDraw.frameID;
    manager->bufferForDraw.frameID++;

    jlong endTime = GetJavaTimeNanos();
    WLBufferTrace(manager, "SendShowBufferToWayland (%lldns)", endTime - startTime);
    WLBufferTraceFrame(manager);
}

static void
CopyDamagedArea(WLSurfaceBufferManager * manager, jint x, jint y, jint width, jint height)
{
    assert(manager->bufferForShow.wlSurfaceBuffer);
    assert(manager->bufferForDraw.width == manager->bufferForShow.wlSurfaceBuffer->width);
    assert(manager->bufferForDraw.height == manager->bufferForShow.wlSurfaceBuffer->height);
    assert(x >= 0);
    assert(y >= 0);
    assert(width >= 0);
    assert(height >= 0);
    assert(height + y >= 0);
    assert(width + x >= 0);
    assert(width <= manager->bufferForDraw.width);
    assert(height <= manager->bufferForDraw.height);

    pixel_t * dest = manager->bufferForShow.wlSurfaceBuffer->data;
    pixel_t * src  = manager->bufferForDraw.data;

    for (jint i = y; i < height + y; i++) {
        pixel_t * dest_row = &dest[i * manager->bufferForDraw.width];
        pixel_t * src_row  = &src [i * manager->bufferForDraw.width];
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
    MUTEX_LOCK(manager->drawLock);

    assert(manager->bufferForShow.wlSurfaceBuffer);
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

    MUTEX_UNLOCK(manager->drawLock);
}

static void
DrawBufferCreate(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    assert(manager->bufferForDraw.data == NULL);
    assert(manager->bufferForDraw.damageList == NULL);

    manager->bufferForDraw.frameID++;
    manager->bufferForDraw.manager = manager;
    manager->bufferForDraw.data = malloc(DrawBufferSizeInBytes(manager));

    for (jint i = 0; i < DrawBufferSizeInPixels(manager); ++i) {
        manager->bufferForDraw.data[i] = manager->bgPixel;
    }
}

static void
DrawBufferDestroy(WLSurfaceBufferManager * manager)
{
    free(manager->bufferForDraw.data);
    manager->bufferForDraw.data = NULL;
    DamageList_FreeAll(manager->bufferForDraw.damageList);
    manager->bufferForDraw.damageList = NULL;
}

WLSurfaceBufferManager *
WLSBM_Create(jint width, jint height, jint scale, jint bgPixel, jint wlShmFormat)
{
    traceEnabled = getenv("J2D_STATS");
    traceFPSEnabled = getenv("J2D_FPS");

    WLSurfaceBufferManager * manager = calloc(1, sizeof(WLSurfaceBufferManager));
    if (!manager) {
        return NULL;
    }

    manager->bufferForDraw.width = width;
    manager->bufferForDraw.height = height;
    manager->scale = scale;
    manager->bgPixel = bgPixel;
    manager->format = wlShmFormat;

    pthread_mutex_init(&manager->showLock, NULL);

    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    // Recursive mutex is required because the same "draw" buffer can be
    // both read from and written to (when scrolling, for instance).
    // So it needs to be able to be "locked" twice: once for writing and
    // once for reading.
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&manager->drawLock, &attr);

    MUTEX_LOCK(manager->drawLock); // satisfy assertions
    DrawBufferCreate(manager);
    MUTEX_UNLOCK(manager->drawLock);

    MUTEX_LOCK(manager->showLock); // satisfy assertions
    ShowBufferCreate(manager);
    MUTEX_UNLOCK(manager->showLock);

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
        manager->isBufferAttached = false;
        // The "frame" callback depends on the surface; when changing the surface,
        // cancel any associated pending callbacks:
        CancelFrameCallback(manager);
    } else {
        assert(manager->wlSurface == wl_surface);
    }
    MUTEX_UNLOCK(manager->showLock);
}

void
WLSBM_Destroy(WLSurfaceBufferManager * manager)
{
    J2dTrace(J2D_TRACE_INFO, "WLSBM_Destroy: manger %p\n", manager);

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
    MUTEX_LOCK(manager->drawLock);
    return &manager->bufferForDraw;
}

void
WLSBM_BufferReturn(WLSurfaceBufferManager * manager, WLDrawBuffer * buffer)
{
    if (&manager->bufferForDraw == buffer) {
        MUTEX_UNLOCK(buffer->manager->drawLock);
        WLBufferTrace(manager, "WLSBM_BufferReturn(%d)", manager->bufferForDraw.frameID);
    } else {
        WL_FATAL_ERROR("WLSBM_BufferReturn() called with an unidentified buffer");
    }
}

void
WLSBM_SurfaceCommit(WLSurfaceBufferManager * manager)
{
    MUTEX_LOCK(manager->showLock);

    const bool frameCallbackScheduled = manager->wl_frame_callback != NULL;

    WLBufferTrace(manager, "WLSBM_SurfaceCommit (%x, %s)",
                  manager->wlSurface,
                  frameCallbackScheduled ? "wait for frame" : "now");

    if (manager->wlSurface && !frameCallbackScheduled) {
        bool canScheduleFrameCallback = manager->isBufferAttached;
        // Don't always send the frame immediately so as not to overwhelm Wayland
        bool sendNow = !canScheduleFrameCallback;
        TrySendShowBufferToWayland(manager, sendNow);
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
WLSBM_SizeChangeTo(WLSurfaceBufferManager * manager, jint width, jint height, jint scale)
{
    MUTEX_LOCK(manager->drawLock);

    const bool size_changed = manager->bufferForDraw.width != width || manager->bufferForDraw.height != height;
    if (size_changed) {
        DrawBufferDestroy(manager);

        manager->bufferForDraw.width  = width;
        manager->bufferForDraw.height = height;

        ShowBufferInvalidateForNewSize(manager);
        DrawBufferCreate(manager);
        WLBufferTrace(manager, "WLSBM_SizeChangeTo %dx%d", width, height);
    }

    MUTEX_LOCK(manager->showLock);
    if (manager->scale != scale) {
        manager->scale = scale;
    }
    MUTEX_UNLOCK(manager->showLock);

    MUTEX_UNLOCK(manager->drawLock);
}

#endif
