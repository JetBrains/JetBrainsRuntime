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

#ifndef HEADLESS

typedef struct WLSurfaceBuffer WLSurfaceBuffer;

extern struct wl_shm_pool *
CreateShmPool(size_t size, const char *name, void **data); // defined in WLToolkit.c

static bool
TrySendDrawBufferToWayland(WLSurfaceBufferManager * manager);

static WLSurfaceBuffer *
SurfaceBufferCreate(WLSurfaceBufferManager * manager);

static void
SurfaceBufferNotifyReleased(WLSurfaceBufferManager * manager, struct wl_buffer * wl_buffer);

static bool
ShowBufferIsAvailable(WLSurfaceBufferManager * manager);

static void
ShowBufferPrepareFreshOneWithPixelsFrom(WLSurfaceBufferManager * manager, WLSurfaceBuffer * lastBuffer);

static void
ShowBufferInvalidateForNewSize(WLSurfaceBufferManager * manager);

static void
SurfaceBufferDestroy(WLSurfaceBuffer * buffer);

static void
ScheduleFrameCallback(WLSurfaceBufferManager * manager);

static inline void
RegisterFrameLost(const char* reason)
{
    if (getenv("J2D_STATS")) {
        fprintf(stderr, "WLBuffers: frame lost, reason '%s'\n", reason);
        fflush(stderr);
    }
}

static inline void
ReportFatalError(const char* file, int line, const char *msg)
{
    fprintf(stderr, "Fatal error at %s:%d: %s\n", file, line, msg);
    fflush(stderr);
    assert(0);
}

static inline void
AssertCalledOnEDT(const char* file, int line)
{
    char threadName[16];
    pthread_getname_np(pthread_self(), threadName, sizeof(threadName));
    if (strncmp(threadName, "AWT-EventQueue", 14) != 0) {
        fprintf(stderr, "Assert failed (called on %s instead of EDT) at %s:%d\n", threadName, file, line);
        fflush(stderr);
        assert(0);
    }
}

static void
AssertDrawLockIsHeld(WLSurfaceBufferManager* manager, const char * file, int line);

#define ASSERT_ON_EDT() AssertCalledOnEDT(__FILE__, __LINE__)
#define WL_FATAL_ERROR(msg) ReportFatalError(__FILE__, __LINE__, msg)
#define ASSERT_DRAW_LOCK_IS_HELD(manager) AssertDrawLockIsHeld(manager, __FILE__, __LINE__)
#define ASSERT_SHOW_LOCK_IS_HELD(manager) AssertShowLockIsHeld(manager, __FILE__, __LINE__)

#define MUTEX_LOCK(m)   if (pthread_mutex_lock(&(m)))   { WL_FATAL_ERROR("Failed to lock mutex"); }
#define MUTEX_UNLOCK(m) if (pthread_mutex_unlock(&(m))) { WL_FATAL_ERROR("Failed to unlock mutex"); }

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
 * There's one and only buffer attached to the surface that Wayland reads from,
 * which is allocated in shared memory: bufferForShow.
 *
 * There's one buffer (but it possible to have more) that can be used for drawing.
 * When Wayland is ready to receive updates, the modified portions of that buffer
 * are copied over to bufferForShow.
 *
 * The size of bufferForShow is determined by width and height fields; the size of
 * bufferForShow can lag behind and will re-adjust after Wayland has released it
 * back to us.
 */
struct WLSurfaceBufferManager {
    struct wl_surface * wlSurface;      // only accessed under showLock
    int                 bgPixel;
    int                 format;         // one of enum wl_shm_format

    /**
     * ID of the "drawing" frame to be sent to Wayland.
     * Is set by committing a new frame with WLSBM_SurfaceCommit().
     * Gets re-set to 0 right after sending to Wayland.
     */
    frame_id_t          commitFrameID;  // only accessed under showLock
    struct wl_callback* wl_frame_callback; // only accessed under showLock

    pthread_mutex_t     showLock;

    /**
     * The "show" buffer that is next to be sent to Wayland for showing on the screen.
     * When sent to Wayland, its WLSurfaceBuffer is added to the buffersInUse list
     * and a fresh one created or re-used from the buffersFree list so that
     * this buffer is available at all times.
     * When "draw" buffer (bufferForDraw) size is changed, this one is
     * immediately invalidated along with all those from the buffersFree list.
     */
    WLShowBuffer        bufferForShow; // only accessed under showLock

    /// A list of buffers that can be re-used as bufferForShow.wlSurfaceBuffer.
    WLSurfaceBuffer *   buffersFree;   // only accessed under showLock

    /// A list of buffers sent to Wayland and not yet released; when released,
    /// they may be added to the buffersFree list.
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
    // TODO: would be nice to be able to check the mutex owner
}

static inline void
AssertShowLockIsHeld(WLSurfaceBufferManager* manager, const char * file, int line)
{
    if (pthread_mutex_trylock(&manager->showLock) == 0) {
        fprintf(stderr, "showLock not acquired at %s:%d\n", file, line);
        fflush(stderr);
        assert(0);
    }
}

static inline void
ShowFrameNumbers(WLSurfaceBufferManager* manager, const char *fmt, ...)
{
    // TODO: this is temporary debugging code that will be removed in the future
    if (getenv("J2D_TRACE_LEVEL")) {
        va_list args;
        va_start(args, fmt);
        fprintf(stderr, ">>> ");
        vfprintf(stderr, fmt, args);
        fprintf(stderr, "; showing frame %d, drawing frame %d, frame to be committed %d\n",
                manager->bufferForShow.frameID,
                manager->bufferForDraw.frameID,
                manager->commitFrameID);
        fflush(stderr);
        va_end(args);
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

static inline jint
SurfaceBufferSizeInPixels(WLSurfaceBuffer * buffer)
{
    return buffer->width * buffer->height;
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
    SurfaceBufferNotifyReleased(manager, wl_buffer);

    ShowFrameNumbers(manager, "wl_buffer_release");
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

    memset(buffer, 0, sizeof(WLSurfaceBuffer));

    free(buffer);
}

static WLSurfaceBuffer *
SurfaceBufferCreate(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    WLSurfaceBuffer * buffer = calloc(1, sizeof(WLSurfaceBuffer));
    if (!buffer) return NULL;

    buffer->width = manager->bufferForDraw.width;
    buffer->height = manager->bufferForDraw.height;

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

    return manager->bufferForShow.wlSurfaceBuffer;
}

static void
ShowBufferCreate(WLSurfaceBufferManager * manager)
{
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


/**
 * Makes sure that there's a fresh "show" buffer of suitable size available
 * that can be sent to Wayland. That fresh buffer's pixels are copied over
 * from the given buffer.
 */
static void
ShowBufferPrepareFreshOneWithPixelsFrom(WLSurfaceBufferManager * manager, WLSurfaceBuffer * lastBuffer)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    assert(lastBuffer);

    ShowBufferPrepareFreshOne(manager);

    if (manager->bufferForShow.wlSurfaceBuffer) {
        // Copy the last image data from the buffer provided.
        const bool sameSize =
                manager->bufferForShow.wlSurfaceBuffer->width == lastBuffer->width
                && manager->bufferForShow.wlSurfaceBuffer->height == lastBuffer->height;
        assert(sameSize);

        // TODO: better do memcpy
        for (jint i = 0; i < SurfaceBufferSizeInPixels(lastBuffer); ++i) {
            manager->bufferForShow.wlSurfaceBuffer->data[i] = lastBuffer->data[i];
        }
    }
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
    }

    while (manager->buffersFree) {
        WLSurfaceBuffer * next = manager->buffersFree->next;
        SurfaceBufferDestroy(manager->buffersFree);
        manager->buffersFree = next;
    }

    ShowBufferCreate(manager);

    MUTEX_UNLOCK(manager->showLock);
}

static void
wl_frame_callback_done(void * data,
                       struct wl_callback * wl_callback,
                       uint32_t callback_data)
{
    WLSurfaceBufferManager * manager = (WLSurfaceBufferManager *) data;
    MUTEX_LOCK(manager->showLock);
    assert(manager->wl_frame_callback == wl_callback);
    wl_callback_destroy(manager->wl_frame_callback);
    manager->wl_frame_callback = NULL;

    struct wl_surface * wlSurface = manager->wlSurface;
    if (wlSurface) {
        // Wayland is ready to get a new frame from us. Send whatever we have
        // managed to draw by this time (maybe nothing).
        if (!TrySendDrawBufferToWayland(manager)) {
            // Re-schedule the same callback if we were unable to send the
            // new frame to Wayland.
            ScheduleFrameCallback(manager);
        }

        ShowFrameNumbers(manager, "wl_frame_callback_done");
        // Need to commit either the damage done to the surface or the re-scheduled
        // callback.
        wl_surface_commit(wlSurface);
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

    if (!manager->wl_frame_callback) {
        manager->wl_frame_callback = wl_surface_frame(manager->wlSurface);
        wl_callback_add_listener(manager->wl_frame_callback, &wl_frame_callback_listener, manager);
    }
}

/**
 * Copies all the damaged areas from the drawing buffer to show buffer and
 * transfers manager to the "new frame sent" state.
 *
 * Returns true if a buffer (possibly with damage) was attached to the managed
 * Wayland surface and false otherwise.
 */
static bool
SendShowBufferToWayland(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    if (manager->wlSurface) { // may get called without an associated wl_surface
        WLSurfaceBuffer * buffer = manager->bufferForShow.wlSurfaceBuffer;
        assert(buffer);

        ShowBufferPrepareFreshOneWithPixelsFrom(manager, buffer);

        // wl_buffer_listener will release bufferForShow when Wayland's done with it
        wl_surface_attach(manager->wlSurface, buffer->wlBuffer, 0, 0);
        wl_surface_set_buffer_scale(manager->wlSurface, manager->scale);

        DamageList_SendAll(manager->bufferForShow.damageList, manager->wlSurface);
        DamageList_FreeAll(manager->bufferForShow.damageList);
        manager->bufferForShow.damageList = NULL;

        buffer->next = manager->buffersInUse;
        manager->buffersInUse = buffer;

        manager->bufferForShow.frameID = manager->bufferForDraw.frameID;
        manager->bufferForDraw.frameID++;
        manager->commitFrameID = 0;
        return true;
    }

    return false;
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
 * Copies areas from the current damageList of the drawing surface to
 * the buffer associated with the Wayland surface for displaying.
 *
 * Clears the list of damaged areas from the drawing buffer and
 * moves that list to the displaying buffer so that Wayland can get
 * notified of what has changed in the buffer.
 */
static bool
CopyDamagedAreasToShowBuffer(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    assert(manager->bufferForShow.wlSurfaceBuffer);
    assert(manager->bufferForShow.damageList == NULL);

    const bool willCommit = (manager->bufferForDraw.damageList != NULL) && manager->wlSurface != NULL;
    if (willCommit) {
	    manager->bufferForShow.damageList = manager->bufferForDraw.damageList;
	    manager->bufferForDraw.damageList = NULL;

	    for (DamageList* l = manager->bufferForShow.damageList; l; l = l->next) {
		    CopyDamagedArea(manager, l->x, l->y, l->width, l->height);
	    }
    }

    return willCommit;
}

/**
 * Attempts to send damaged areas in the drawing buffer from the frame specified
 * by manager->commitFrameID to Wayland by copying them to the show buffer
 * and notifying Wayland of that.
 *
 * Returns false if another attempt to send this frame must be scheduled
 * and true otherwise (a new frame is expected).
 */
static bool
TrySendDrawBufferToWayland(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    if (manager->commitFrameID != manager->bufferForDraw.frameID) {
        RegisterFrameLost("Attempt to show a frame with ID different from the one we committed");
        ShowFrameNumbers(manager, "TrySendDrawBufferToWayland - skipped frame");
        return true;
    }

    bool needAnotherTry = true;
    ShowFrameNumbers(manager, "TrySendDrawBufferToWayland");

    const bool bufferForDrawWasLocked = pthread_mutex_trylock(&manager->drawLock);
    if (bufferForDrawWasLocked) {
        // Can't display the buffer with new pixels, so let's give what we already have.
        RegisterFrameLost("Repeating last frame while the new one isn't ready yet");
    } else { // bufferForDraw was not locked, but is locked now
        if (ShowBufferIsAvailable(manager)) { // may be out of memory
            const bool needCommit = CopyDamagedAreasToShowBuffer(manager);
            pthread_mutex_unlock(&manager->drawLock);
            if (needCommit) {
                needAnotherTry = !SendShowBufferToWayland(manager);
            } else {
                needAnotherTry = false; // nothing to show, wait for the next WLSBM_SurfaceCommit()
            }
        } else {
            pthread_mutex_unlock(&manager->drawLock);
        }
    }

    return !needAnotherTry;
}

static void
DrawBufferCreate(WLSurfaceBufferManager * manager)
{
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
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE_NP);
    pthread_mutex_init(&manager->drawLock, &attr);
    DrawBufferCreate(manager);
    ShowBufferCreate(manager);

    J2dTrace(J2D_TRACE_INFO, "WLSBM_Create: created %p for %dx%d px\n", manager, width, height);
    return manager;
}

void
WLSBM_SurfaceAssign(WLSurfaceBufferManager * manager, struct wl_surface* wl_surface)
{
    J2dTrace(J2D_TRACE_INFO, "WLSBM_SurfaceAssign: assigned surface %p to manger %p\n", wl_surface, manager);

    MUTEX_LOCK(manager->showLock);
    manager->wlSurface = wl_surface;
    MUTEX_UNLOCK(manager->showLock);
}

void
WLSBM_Destroy(WLSurfaceBufferManager * manager)
{
    J2dTrace(J2D_TRACE_INFO, "WLSBM_Destroy: manger %p\n", manager);

    // NB: must never be called in parallel with the Wayland event handlers
    MUTEX_LOCK(manager->showLock);
    MUTEX_LOCK(manager->drawLock);
    if (manager->wl_frame_callback) {
        wl_callback_destroy(manager->wl_frame_callback);
    }
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
    MUTEX_LOCK(manager->drawLock);

    // We are going to "damage" the drawing frame now and therefore
    // shall not commit until WLSBM_SurfaceCommit() is issued.
    // Setting commitFrameID to a number that no draw frame has
    // achieves just that.

    // This effectively disables redrawing during very fast interactive
    // resize of applications that draw often (like animated pictures)
    // as new frames often appear after the size had been changed, but before
    // the change has been committed to Wayland. In this case we don't
    // commit and wait for a finished frame, which may not come quick
    // enough before the next size change, which re-starts the same cycle.
    MUTEX_LOCK(manager->showLock);
    manager->commitFrameID = 0;
    MUTEX_UNLOCK(manager->showLock);
    return &manager->bufferForDraw;
}

void
WLSBM_BufferReturn(WLSurfaceBufferManager * manager, WLDrawBuffer * buffer)
{
    if (&manager->bufferForDraw == buffer) {
        MUTEX_UNLOCK(buffer->manager->drawLock);
        ShowFrameNumbers(manager, "WLSBM_BufferReturn");
    } else {
        WL_FATAL_ERROR("WLSBM_BufferReturn() called with an unidentified buffer");
    }
}

void
WLSBM_SurfaceCommit(WLSurfaceBufferManager * manager)
{
    MUTEX_LOCK(manager->showLock);
    // Request that the frame with this ID to be committed to Wayland
    // and no other. Any attempt to draw on this frame will cancel
    // the commit.
    manager->commitFrameID = manager->bufferForDraw.frameID;

    ShowFrameNumbers(manager, "WLSBM_SurfaceCommit");
    if (manager->wlSurface && manager->wl_frame_callback == NULL) {
        // Wayland is ready to get a new frame from us. Send whatever we have
        // managed to draw by this time (maybe nothing).
        if (!TrySendDrawBufferToWayland(manager)) {
            // Re-schedule the same callback if we were unable to send the
            // new frame to Wayland. This can happen, for instance, if Wayland
            // haven't released the surface buffer to us yet.
            ScheduleFrameCallback(manager);
        }

        ShowFrameNumbers(manager, "wl_frame_callback_done");
        // Need to commit either the damage done to the surface or the re-scheduled
        // callback.
        wl_surface_commit(manager->wlSurface);
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
    ShowFrameNumbers(buffer->manager, "WLSB_Damage (at %d, %d %dx%d)", x, y, width, height);
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
        ShowFrameNumbers(manager, "WLSBM_SizeChangeTo %dx%d", width, height);
    }

    MUTEX_LOCK(manager->showLock);
    if (manager->scale != scale) {
        manager->scale = scale;
    }
    MUTEX_UNLOCK(manager->showLock);

    MUTEX_UNLOCK(manager->drawLock);
}

#endif
