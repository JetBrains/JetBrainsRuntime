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
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <pthread.h>
#include <assert.h>

#include "Trace.h"
#include "jni_util.h"

#include "WLBuffers.h"

#ifndef HEADLESS

extern struct wl_shm *wl_shm; // defined in WLToolkit.c

static void
BufferShowToWayland(WLSurfaceBufferManager * manager);

static void
SurfaceBufferCreate(WLSurfaceBufferManager * manager, size_t newSize);

static void
SurfaceBufferDestroy(WLSurfaceBufferManager * manager, bool destroyPool);

static void
SurfaceBufferAdjust(WLSurfaceBufferManager * manager);

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
    DamageList* item = malloc(sizeof(DamageList));
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

typedef enum WLSurfaceBufferState {
    /// The buffer has no valid content yet.
    WL_BUFFER_NEW      = 0,
    /// The buffer is being read by Wayland and must not be modified by us.
    WL_BUFFER_LOCKED   = 1,
    /// The buffer was read by Wayland and subsequently released to us.
    /// Can be modified now. Still has valid content.
    WL_BUFFER_RELEASED = 2
} WLSurfaceBufferState;

/**
 * Contains data needed to maintain a wl_buffer instance.
 *
 * This buffer is usually attached to a wl_surface.
 * Its size determines the size of the surface.
 */
typedef struct WLSurfaceBuffer {
    struct wl_shm_pool * wlPool;
    struct wl_buffer *   wlBuffer;
    pixel_t *            data;       /// points to a memory segment shared with Wayland
    size_t               size;       /// size of the memory segment; may be more than necessary
    WLSurfaceBufferState state;
    DamageList *         damageList; /// Areas of the buffer that need to be re-drawn by Wayland
} WLSurfaceBuffer;

/**
 * Represents a buffer to draw in.
 *
 * The underlying pixels array is pointed to by the data field.
 * The changes made by drawing are accumulated in the damageList field.
 */
struct WLDrawBuffer {
    WLSurfaceBufferManager * manager;
    pixel_t *                data;
    DamageList *             damageList;
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
    struct wl_surface * wlSurface;
    int                 backgroundRGB;

    pthread_mutex_t     showLock;
    WLSurfaceBuffer     bufferForShow; // only accessed under showLock, mostly on EDT

    pthread_mutex_t     drawLock;
    WLDrawBuffer        bufferForDraw; // only accessed under drawLock
    jint                width;         // only accessed under drawLock
    jint                height;        // only accessed under drawLock
    bool                sizeChanged;   // only accessed under drawLock
};

static inline void
AssertDrawLockIsHeld(WLSurfaceBufferManager* manager, const char * file, int line)
{
    if (pthread_mutex_trylock(&manager->drawLock) == 0) {
        fprintf(stderr, "drawLock not acquired at %s:%d\n", file, line);
        fflush(stderr);
        assert(0);
    }
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

static void
RandomName(char *buf) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long r = ts.tv_nsec;
    for (int i = 0; i < 6; ++i) {
        buf[i] = 'A' + (r & 15) + (r & 16) * 2;
        r >>= 5;
    }
}

static int
CreateSharedMemoryFile(void) {
    int retries = 100;
    do {
        char name[] = "/jwlshm-XXXXXX";
        RandomName(name + sizeof(name) - 7);
        --retries;
        int fd = shm_open(name, O_RDWR | O_CREAT | O_EXCL, 0600);
        if (fd >= 0) {
            shm_unlink(name);
            return fd;
        }
    } while (retries > 0 && errno == EEXIST);
    return -1;
}

static int
AllocateSharedMemoryFile(size_t size) {
    int fd = CreateSharedMemoryFile();
    if (fd < 0)
        return -1;
    int ret;
    do {
        ret = ftruncate(fd, size);
    } while (ret < 0 && errno == EINTR);
    if (ret < 0) {
        close(fd);
        return -1;
    }
    return fd;
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
    const jint stride = manager->width * (jint)sizeof(pixel_t);
    return stride * manager->height;
}

/**
 * Returns the number of pixels in the "draw" buffer.
 */
static inline jint
DrawBufferSizeInPixels(WLSurfaceBufferManager * manager)
{
    return manager->width * manager->height;
}

/**
 * Returns the number of bytes in the buffer shared
 * with Wayland (the "display" buffer).
 */
static inline size_t
ShowBufferSizeInBytes(WLSurfaceBufferManager * manager)
{
    return manager->bufferForShow.size;
}

static void
wl_buffer_release(void * data, struct wl_buffer * wl_buffer)
{
    /* Sent by the compositor when it's no longer using this buffer */
    WLSurfaceBufferManager *manager = (WLSurfaceBufferManager *) data;

    MUTEX_LOCK(manager->showLock);
    assert(manager->bufferForShow.state == WL_BUFFER_LOCKED);
    manager->bufferForShow.state = WL_BUFFER_RELEASED;
    MUTEX_UNLOCK(manager->showLock);
}

static const struct wl_buffer_listener wl_buffer_listener = {
        .release = wl_buffer_release,
};

static void
wl_frame_callback_done(void * data,
                       struct wl_callback * wl_callback,
                       uint32_t callback_data)
{
    wl_callback_destroy(wl_callback);

    WLSurfaceBufferManager * manager = (WLSurfaceBufferManager *) data;
    // Wayland is ready to get a new frame from us. Send whatever we have
    // managed to draw by this time (maybe nothing).
    BufferShowToWayland(manager);
}

static const struct wl_callback_listener wl_frame_callback_listener = {
        .done = wl_frame_callback_done
};

static void
SendToWayland(WLSurfaceBufferManager * manager)
{
    ASSERT_SHOW_LOCK_IS_HELD(manager);

    if (manager->wlSurface) { // may get called without an associated wl_surface
        assert (manager->bufferForShow.state != WL_BUFFER_LOCKED);
        manager->bufferForShow.state = WL_BUFFER_LOCKED;
        // wl_buffer_listener will release bufferForShow when Wayland's done with it
        wl_surface_attach(manager->wlSurface, manager->bufferForShow.wlBuffer, 0, 0);

        DamageList_SendAll(manager->bufferForShow.damageList, manager->wlSurface);
        DamageList_FreeAll(manager->bufferForShow.damageList);
        manager->bufferForShow.damageList = NULL;

        struct wl_callback *wl_frame_callback = wl_surface_frame(manager->wlSurface);
        wl_callback_add_listener(wl_frame_callback, &wl_frame_callback_listener, manager);
        wl_surface_commit(manager->wlSurface);
    }
}

static inline void
CopyDamagedArea(WLSurfaceBufferManager * manager, jint x, jint y, jint width, jint height)
{
    assert(x >= 0);
    assert(y >= 0);
    assert(width >= 0);
    assert(height >= 0);
    assert(height + y >= 0);
    assert(width + x >= 0);

    pixel_t * dest = manager->bufferForShow.data;
    pixel_t * src  = manager->bufferForDraw.data;

    for (jint i = y; i < height + y; i++) {
        pixel_t * dest_row = &dest[i * manager->width];
        pixel_t * src_row  = &src [i * manager->width];
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
CopyDamagedAreasToShowBuffer(WLSurfaceBufferManager * manager) {
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    assert(manager->bufferForShow.damageList == NULL);

    const bool needCommit = manager->sizeChanged
                            || (manager->bufferForDraw.damageList != NULL)
                            || manager->bufferForShow.state == WL_BUFFER_NEW;
    if (manager->sizeChanged) {
        manager->sizeChanged = false;
        SurfaceBufferAdjust(manager);
    }
    assert(DrawBufferSizeInBytes(manager) <= ShowBufferSizeInBytes(manager));

    manager->bufferForShow.damageList = manager->bufferForDraw.damageList;
    manager->bufferForDraw.damageList = NULL;

    for (DamageList* l = manager->bufferForShow.damageList; l; l = l->next) {
        CopyDamagedArea(manager, l->x, l->y, l->width, l->height);
    }

    return needCommit;
}

static void
BufferShowToWayland(WLSurfaceBufferManager * manager)
{
    MUTEX_LOCK(manager->showLock);
    const bool bufferForDrawWasLocked = pthread_mutex_trylock(&manager->drawLock);
    if (bufferForDrawWasLocked) {
        // Can't display the buffer with new pixels, so let's give
        // what we already have.
        if (manager->bufferForShow.state == WL_BUFFER_NEW) {
            RegisterFrameLost("No old frame to show while the new one isn't ready yet");
        } else {
            // The state is either released or locked already
            RegisterFrameLost("Repeating last frame while the new one isn't ready yet");
        }
    } else { // bufferForDraw was not locked, but is locked now
        if (manager->bufferForShow.state == WL_BUFFER_LOCKED) {
            RegisterFrameLost("New buffer is available, but Wayland hasn't released the old one yet");
            pthread_mutex_unlock(&manager->drawLock);
        } else {
            const bool doSend = CopyDamagedAreasToShowBuffer(manager);
            pthread_mutex_unlock(&manager->drawLock);

            if (doSend) {
                SendToWayland(manager);
            }
        }
    }
    MUTEX_UNLOCK(manager->showLock);
}

static void
SurfaceBufferCreate(WLSurfaceBufferManager * manager, size_t newSize)
{
    const bool createPool = (newSize > ShowBufferSizeInBytes(manager));
    if (createPool) {
        manager->bufferForShow.size = newSize;
        int poolFD = AllocateSharedMemoryFile(newSize);
        if (poolFD == -1) {
            return;
        }
        pixel_t *data = (pixel_t *) mmap(NULL, newSize,
                                         PROT_READ | PROT_WRITE, MAP_SHARED,
                                         poolFD, 0);
        if (data == MAP_FAILED) {
            close(poolFD);
            return;
        }

        manager->bufferForShow.data = data;
        manager->bufferForShow.wlPool = wl_shm_create_pool(wl_shm, poolFD, newSize);
        close(poolFD);
    } else {
        assert(manager->bufferForShow.size >= newSize);
        assert(manager->bufferForShow.data);
        assert(manager->bufferForShow.wlPool);
    }

    const int stride = manager->width * sizeof(pixel_t);
    manager->bufferForShow.wlBuffer = wl_shm_pool_create_buffer(manager->bufferForShow.wlPool, 0,
                                                                manager->width,
                                                                manager->height,
                                                                stride,
                                                                WL_SHM_FORMAT_XRGB8888);
    manager->bufferForShow.state = WL_BUFFER_NEW;
    wl_buffer_add_listener(manager->bufferForShow.wlBuffer,
                           &wl_buffer_listener,
                           manager);
}

static void
SurfaceBufferDestroy(WLSurfaceBufferManager * manager, bool destroyPool)
{
    if (destroyPool) {
        // NB: the server (Wayland) will hold this memory for a bit longer, so it's
        // OK to unmap now without waiting for the "release" event for the buffer
        // from Wayland.
        munmap(manager->bufferForShow.data, manager->bufferForShow.size);
        manager->bufferForShow.size = 0;
        manager->bufferForShow.data = NULL;
        wl_shm_pool_destroy(manager->bufferForShow.wlPool);
        manager->bufferForShow.wlPool = NULL;
    }
    // "Destroying the wl_buffer after wl_buffer.release does not change
    //  the surface contents" (source: wayland.xml)
    wl_buffer_destroy(manager->bufferForShow.wlBuffer);
    manager->bufferForShow.wlBuffer = NULL;
}

static void
SurfaceBufferAdjust(WLSurfaceBufferManager * manager)
{
    assert(manager->bufferForShow.state != WL_BUFFER_LOCKED);
    ASSERT_SHOW_LOCK_IS_HELD(manager);
    ASSERT_DRAW_LOCK_IS_HELD(manager);

    const bool needNewMemory = (DrawBufferSizeInBytes(manager) > ShowBufferSizeInBytes(manager));
    SurfaceBufferDestroy(manager, needNewMemory);
    SurfaceBufferCreate(manager, DrawBufferSizeInBytes(manager));
}

static void
DrawBufferCreate(WLSurfaceBufferManager * manager)
{
    assert(manager->bufferForDraw.data == NULL);

    manager->bufferForDraw.manager = manager;
    manager->bufferForDraw.data = malloc(DrawBufferSizeInBytes(manager));

    // TODO: do we really need this here? Why can't pipelines draw the background?
    for (jint i = 0; i < DrawBufferSizeInPixels(manager); ++i) {
        manager->bufferForDraw.data[i] = manager->backgroundRGB;
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
WLSBM_Create(jint width, jint height, jint rgb)
{
    WLSurfaceBufferManager * manager = calloc(1, sizeof(WLSurfaceBufferManager));
    if (!manager) {
        return NULL;
    }

    manager->width = width;
    manager->height = height;
    manager->backgroundRGB = rgb;

    pthread_mutex_init(&manager->showLock, NULL);
    SurfaceBufferCreate(manager, DrawBufferSizeInBytes(manager));
    if (manager->bufferForShow.wlPool == NULL) {
        free(manager);
        return NULL;
    }

    pthread_mutex_init(&manager->drawLock, NULL);
    DrawBufferCreate(manager);

    J2dTrace3(J2D_TRACE_INFO, "WLSBM_Create: created %p for %dx%d px\n", manager, width, height);
    return manager;
}

void
WLSBM_SurfaceAssign(WLSurfaceBufferManager * manager, struct wl_surface* wl_surface)
{
    J2dTrace2(J2D_TRACE_INFO, "WLSBM_SurfaceAssign: assigned surface %p to manger %p\n", wl_surface, manager);

    manager->wlSurface = wl_surface;
    if (wl_surface) {
        MUTEX_LOCK(manager->showLock); // to avoid committing half-copied bufferForShow
        struct wl_callback *wl_frame_callback = wl_surface_frame(wl_surface);
        wl_callback_add_listener(wl_frame_callback, &wl_frame_callback_listener, manager);
        wl_surface_commit(manager->wlSurface); // commit the listener assignment
        MUTEX_UNLOCK(manager->showLock);
    }
}

void
WLSBM_Destroy(WLSurfaceBufferManager * manager)
{
    J2dTrace1(J2D_TRACE_INFO, "WLSBM_Destroy: manger %p\n", manager);

    pthread_mutex_destroy(&manager->showLock);
    SurfaceBufferDestroy(manager, true);
    DrawBufferDestroy(manager);
    pthread_mutex_destroy(&manager->drawLock);
    free(manager);
}

jint
WLSBM_WidthGet(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);
    return manager->width;
}

jint
WLSBM_HeightGet(WLSurfaceBufferManager * manager)
{
    ASSERT_DRAW_LOCK_IS_HELD(manager);
    return manager->height;
}

WLDrawBuffer *
WLSBM_BufferAcquireForDrawing(WLSurfaceBufferManager * manager)
{
    MUTEX_LOCK(manager->drawLock);
    return &manager->bufferForDraw;
}

void
WLSBM_BufferReturn(WLSurfaceBufferManager * manager, WLDrawBuffer * buffer)
{
    if (&manager->bufferForDraw == buffer) {
        J2dTrace1(J2D_TRACE_INFO, "WLSBM_BufferReturn: manger %p buffer for draw\n", manager);

        MUTEX_UNLOCK(buffer->manager->drawLock);
        BufferShowToWayland(manager);
    } else {
        WL_FATAL_ERROR("WLSBM_BufferReturn() called with an unidentified buffer");
    }
}

void
WLSBM_SurfaceCommit(WLSurfaceBufferManager * manager)
{
    BufferShowToWayland(manager);
}

void
WLSB_Damage(WLDrawBuffer * buffer, jint x, jint y, jint width, jint height)
{
    assert(&buffer->manager->bufferForDraw == buffer); // only one buffer currently supported
    ASSERT_DRAW_LOCK_IS_HELD(buffer->manager);
    assert(x >= 0);
    assert(y >= 0);
    assert(x + width <= buffer->manager->width);
    assert(y + height <= buffer->manager->height);

    buffer->damageList = DamageList_Add(buffer->damageList, x, y, width, height);
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
    MUTEX_LOCK(manager->drawLock);

    if (manager->width != width || manager->height != height) {
        DrawBufferDestroy(manager);

        manager->width = width;
        manager->height = height;
        manager->sizeChanged = true;

        DrawBufferCreate(manager);
    }

    MUTEX_UNLOCK(manager->drawLock);
}

#endif
