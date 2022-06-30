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
#include <wayland-client.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>
#include <pthread.h>
#include <jni.h>
#include "jni_util.h"
#include "Trace.h"
#include "WLSurfaceData.h"
extern struct wl_shm *wl_shm;
//#undef HEADLESS
void logWSDOp(char* str, void* p, jint lockFlags) {
    J2dTrace2(J2D_TRACE_INFO, "%s: %p, ", str, p);
    J2dTrace7(J2D_TRACE_INFO, "[%c%c%c%c%c%c%c]",
              (lockFlags&SD_LOCK_READ) ? 'R' : '.',
              (lockFlags&SD_LOCK_WRITE) ? 'W' : '.',
              (lockFlags&SD_LOCK_LUT) ? 'L' : '.',
              (lockFlags&SD_LOCK_INVCOLOR) ? 'C' : '.',
              (lockFlags&SD_LOCK_INVGRAY) ? 'G' : '.',
              (lockFlags&SD_LOCK_FASTEST) ? 'F' : '.',
              (lockFlags&SD_LOCK_PARTIAL) ? 'P' : '.');
    J2dTrace(J2D_TRACE_INFO, "\n");
}

typedef struct _WLSDPrivate {
    jint                lockFlags;
    struct wl_buffer*   wlBuffer;
} WLSDPrivate;

JNIEXPORT WLSDOps * JNICALL
WLSurfaceData_GetOps(JNIEnv *env, jobject sData)
{
#ifdef HEADLESS
    return NULL;
#else
    SurfaceDataOps *ops = SurfaceData_GetOps(env, sData);
    if (ops == NULL) {
        SurfaceData_ThrowInvalidPipeException(env, "not an valid WLSurfaceData");
    }
    return (WLSDOps *) ops;
#endif /* !HEADLESS */
}
#ifndef HEADLESS
static void
wl_buffer_release(void *data, struct wl_buffer *wl_buffer) {
    /* Sent by the compositor when it's no longer using this buffer */
    wl_buffer_destroy(wl_buffer);
}

static const struct wl_buffer_listener wl_buffer_listener = {
        .release = wl_buffer_release,
};


/* Shared memory support code (from  https://wayland-book.com/) */
static void randname(char *buf) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long r = ts.tv_nsec;
    for (int i = 0; i < 6; ++i) {
        buf[i] = 'A' + (r & 15) + (r & 16) * 2;
        r >>= 5;
    }
}

static int create_shm_file() {
    int retries = 100;
    do {
        char name[] = "/wl_shm-XXXXXX";
        randname(name + sizeof(name) - 7);
        --retries;
        int fd = shm_open(name, O_RDWR | O_CREAT | O_EXCL, 0600);
        if (fd >= 0) {
            shm_unlink(name);
            return fd;
        }
    } while (retries > 0 && errno == EEXIST);
    return -1;
}

static int allocate_shm_file(size_t size) {
    int fd = create_shm_file();
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

#endif

/*
 * Class:     sun_java2d_wl_WLSurfaceData
 * Method:    initSurface
 * Signature: (IIIJ)V
 */
JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_initSurface(JNIEnv *env, jobject wsd,
                                             jobject peer,
                                             jint rgb,
                                             jint width, jint height)
{
#ifndef HEADLESS
    J2dTrace6(J2D_TRACE_INFO, "WLSurfaceData_initSurface: %dx%d, rgba=(%d,%d,%d,%d)\n",
              width, height, rgb&0xff, (rgb>>8)&0xff, (rgb>>16)&0xff, (rgb>>24)&0xff);
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }
    jboolean hasException;
    if (!wsdo->wlSurface) {
        wsdo->wlSurface = JNU_CallMethodByName(env, &hasException, peer, "getWLSurface", "()J").j;
    }
    if (width <= 0) {
        width = 1;
    }
    if (height <= 0) {
        height = 1;
    }
    int stride = width * 4;
    int size = stride * height;

    wsdo->fd = allocate_shm_file(size);
    if (wsdo->fd == -1) {
        return;
    }
    wsdo->data = (uint32_t *) (mmap(NULL, size,
                                    PROT_READ | PROT_WRITE, MAP_SHARED, wsdo->fd, 0));
    if (wsdo->data == MAP_FAILED) {
        close(wsdo->fd);
        return;
    }

    wsdo->dataSize = size;
    wsdo->width = width;
    wsdo->height = height;

    for (int i = 0; i < height*width; ++i) {
        wsdo->data[i] = rgb;
    }

    wsdo->wlShmPool = (jlong)wl_shm_create_pool(wl_shm, wsdo->fd, size);
    wsdo->wlBuffer = (jlong) wl_shm_pool_create_buffer(
            (struct wl_shm_pool*)wsdo->wlShmPool, 0, width, height, stride, WL_SHM_FORMAT_XRGB8888);

    wl_surface_attach((struct wl_surface*)wsdo->wlSurface, (struct wl_buffer*)wsdo->wlBuffer, 0, 0);
    wl_surface_commit((struct wl_surface*)wsdo->wlSurface);
#endif /* !HEADLESS */
}

/**
 * This is the implementation of the general surface LockFunc defined in
 * SurfaceData.h.
 */
jint
WLSD_Lock(JNIEnv *env,
           SurfaceDataOps *ops,
           SurfaceDataRasInfo *pRasInfo,
           jint lockflags)
{
#ifndef HEADLESS
    WLSDOps *wlso = (WLSDOps*)ops;
    logWSDOp("WLSD_Lock", wlso, lockflags);
    pthread_mutex_lock(&wlso->lock);
    WLSDPrivate *priv = (WLSDPrivate *) &(pRasInfo->priv);
    priv->lockFlags = lockflags;

    if (priv->lockFlags & SD_LOCK_WRITE) {
        pRasInfo->rasBase = wlso->data;
        pRasInfo->pixelStride = 4;
        pRasInfo->pixelBitOffset = 0;
        pRasInfo->scanStride = 4 * wlso->width;
    }
#endif
    return SD_SUCCESS;
}


static void WLSD_GetRasInfo(JNIEnv *env,
                             SurfaceDataOps *ops,
                             SurfaceDataRasInfo *pRasInfo)
{
#ifndef HEADLESS
    WLSDPrivate *priv = (WLSDPrivate *) &(pRasInfo->priv);
    WLSDOps *wlso = (WLSDOps*)ops;
    logWSDOp("WLSD_GetRasInfo", wlso, priv->lockFlags);
    if (priv->lockFlags & SD_LOCK_WRITE) {
        wl_surface_damage ((struct wl_surface*)wlso->wlSurface, pRasInfo->bounds.x1, pRasInfo->bounds.y1,
                           pRasInfo->bounds.x2 - pRasInfo->bounds.x1, pRasInfo->bounds.y2 - pRasInfo->bounds.y1);
    }
#endif
}

static void WLSD_Unlock(JNIEnv *env,
                         SurfaceDataOps *ops,
                         SurfaceDataRasInfo *pRasInfo)
{
#ifndef HEADLESS
    WLSDOps *wsdo = (WLSDOps*)ops;
    J2dTrace1(J2D_TRACE_INFO, "WLSD_Unlock: %p\n", wsdo);
    wl_surface_commit((struct wl_surface*)wsdo->wlSurface);
    pthread_mutex_unlock(&wsdo->lock);
#endif
}

static void WLSD_Dispose(JNIEnv *env, SurfaceDataOps *ops)
{
#ifndef HEADLESS
    /* ops is assumed non-null as it is checked in SurfaceData_DisposeOps */
    J2dTrace1(J2D_TRACE_INFO, "WLSD_Dispose %p\n", ops);
    WLSDOps *wsdo = (WLSDOps*)ops;
    if (wsdo->wlSurface != 0) {
        close(wsdo->fd);
        wsdo->fd = 0;
        munmap(wsdo->data, wsdo->dataSize);
        wl_shm_pool_destroy((struct wl_shm_pool *) wsdo->wlShmPool);
        wl_buffer_add_listener((struct wl_buffer *) wsdo->wlBuffer, &wl_buffer_listener, NULL);
    } else {
        J2dTrace(J2D_TRACE_INFO, "WLSD_Dispose: wlSurface == 0\n");
    }
    pthread_mutex_destroy(&wsdo->lock);
#endif
}
/*
 * Class:     sun_java2d_wl_WLSurfaceData
 * Method:    initOps
 * Signature: (Ljava/lang/Object;Ljava/lang/Object;I)V
 */
JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_initOps(JNIEnv *env, jobject wsd,
                                         jobject peer,
                                         jobject graphicsConfig, jint depth)
{
#ifndef HEADLESS

    WLSDOps *wsdo = (WLSDOps*)SurfaceData_InitOps(env, wsd, sizeof(WLSDOps));
    J2dTrace1(J2D_TRACE_INFO, "WLSurfaceData_initOps: %p\n", wsdo);
    jboolean hasException;
    if (wsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    wsdo->sdOps.Lock = WLSD_Lock;
    wsdo->sdOps.Unlock = WLSD_Unlock;
    wsdo->sdOps.GetRasInfo = WLSD_GetRasInfo;
    wsdo->sdOps.Dispose = WLSD_Dispose;

    wsdo->wlSurface = 0;
    wsdo->bgPixel = 0;
    wsdo->isBgInitialized = JNI_FALSE;
    pthread_mutex_init(&wsdo->lock, NULL);

#endif /* !HEADLESS */
}