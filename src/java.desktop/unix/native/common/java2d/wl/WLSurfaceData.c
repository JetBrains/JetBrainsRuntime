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

#include <string.h>
#include <pthread.h>
#include <wayland-client.h>

#include "jni.h"
#include "jni_util.h"

#include "awt.h"
#include "Trace.h"
#include "WLSurfaceData.h"
#include "WLBuffers.h"

struct WLSDOps {
    SurfaceDataOps      sdOps;
    WLSurfaceBufferManager * bufferManager;
    pthread_mutex_t     lock;
};

void
logWSDOp(char* str, void* p, jint lockFlags)
{
    J2dTrace(J2D_TRACE_INFO, "%s: %p, ", str, p);
    J2dTrace(J2D_TRACE_INFO, "[%c%c%c%c%c%c%c]",
             (lockFlags&SD_LOCK_READ) ? 'R' : '.',
             (lockFlags&SD_LOCK_WRITE) ? 'W' : '.',
             (lockFlags&SD_LOCK_LUT) ? 'L' : '.',
             (lockFlags&SD_LOCK_INVCOLOR) ? 'C' : '.',
             (lockFlags&SD_LOCK_INVGRAY) ? 'G' : '.',
             (lockFlags&SD_LOCK_FASTEST) ? 'F' : '.',
             (lockFlags&SD_LOCK_PARTIAL) ? 'P' : '.');
    J2dTrace(J2D_TRACE_INFO, "\n");
}

typedef struct WLSDPrivate {
    jint           lockFlags;
    WLDrawBuffer * wlBuffer;
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

JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_assignSurface(JNIEnv *env, jobject wsd,
                                             jlong wlSurfacePtr)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLSurfaceData_assignSurface\n");
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }

    WLSBM_SurfaceAssign(wsdo->bufferManager, (struct wl_surface*)jlong_to_ptr(wlSurfacePtr));
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_commitToServer(JNIEnv *env, jobject wsd)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLSurfaceData_commitToServer\n");
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }

    WLSBM_SurfaceCommit(wsdo->bufferManager);
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_revalidate(JNIEnv *env, jobject wsd,
                                             jint width, jint height)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLSurfaceData_revalidate to size %d x %d\n", width, height);
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }

    WLSBM_SizeChangeTo(wsdo->bufferManager, width, height);
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
    priv->wlBuffer = WLSBM_BufferAcquireForDrawing(wlso->bufferManager);

    J2dTrace(J2D_TRACE_INFO, "WLSD_Lock() at %d, %d for %dx%d\n",
             pRasInfo->bounds.x1, pRasInfo->bounds.y1,
             pRasInfo->bounds.x2 - pRasInfo->bounds.x1,
             pRasInfo->bounds.y2 - pRasInfo->bounds.y1);
    SurfaceData_IntersectBoundsXYWH(&pRasInfo->bounds,
                                    0,
                                    0,
                                    WLSBM_WidthGet(wlso->bufferManager),
                                    WLSBM_HeightGet(wlso->bufferManager));
#endif
    return SD_SUCCESS;
}


static void
WLSD_GetRasInfo(JNIEnv *env,
                SurfaceDataOps *ops,
                SurfaceDataRasInfo *pRasInfo)
{
#ifndef HEADLESS
    WLSDPrivate *priv = (WLSDPrivate *) &(pRasInfo->priv);
    WLSDOps *wlso = (WLSDOps*)ops;
    logWSDOp("WLSD_GetRasInfo", wlso, priv->lockFlags);

    if (priv->lockFlags & SD_LOCK_RD_WR) {
        pRasInfo->rasBase = WLSB_DataGet(priv->wlBuffer);
        pRasInfo->pixelStride = sizeof(pixel_t);
        pRasInfo->pixelBitOffset = 0;
        pRasInfo->scanStride = sizeof(pixel_t) * WLSBM_WidthGet(wlso->bufferManager);
    } else {
        pRasInfo->rasBase = NULL;
    }

    pRasInfo->lutBase = NULL;
    pRasInfo->invColorTable = NULL;
    pRasInfo->redErrTable = NULL;
    pRasInfo->grnErrTable = NULL;
    pRasInfo->bluErrTable = NULL;
    pRasInfo->invGrayTable = NULL;

    if (priv->lockFlags & SD_LOCK_WRITE) {
        WLSB_Damage(priv->wlBuffer, pRasInfo->bounds.x1, pRasInfo->bounds.y1,
                    pRasInfo->bounds.x2 - pRasInfo->bounds.x1, pRasInfo->bounds.y2 - pRasInfo->bounds.y1);
    }
#endif
}

static void
WLSD_Unlock(JNIEnv *env,
            SurfaceDataOps *ops,
            SurfaceDataRasInfo *pRasInfo)
{
#ifndef HEADLESS
    WLSDOps *wsdo = (WLSDOps*)ops;
    J2dTrace(J2D_TRACE_INFO, "WLSD_Unlock: %p\n", wsdo);
    WLSDPrivate *priv = (WLSDPrivate *) &(pRasInfo->priv);
    WLSBM_BufferReturn(wsdo->bufferManager, priv->wlBuffer);
    pthread_mutex_unlock(&wsdo->lock);
#endif
}

static void
WLSD_Dispose(JNIEnv *env, SurfaceDataOps *ops)
{
#ifndef HEADLESS
    /* ops is assumed non-null as it is checked in SurfaceData_DisposeOps */
    J2dTrace(J2D_TRACE_INFO, "WLSD_Dispose %p\n", ops);
    WLSDOps *wsdo = (WLSDOps*)ops;

    // No Wayland event handlers should be able to run while this method
    // runs. Those handlers may retain a reference to the buffer manager
    // and therefore must be cancelled before that reference becomes stale.
    AWT_LOCK();
    WLSBM_Destroy(wsdo->bufferManager);
    wsdo->bufferManager = NULL;
    AWT_NOFLUSH_UNLOCK();

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
                                         jint width,
                                         jint height,
                                         jint backgroundRGB)
{
#ifndef HEADLESS

    WLSDOps *wsdo = (WLSDOps*)SurfaceData_InitOps(env, wsd, sizeof(WLSDOps));
    J2dTrace(J2D_TRACE_INFO, "WLSurfaceData_initOps: %p\n", wsdo);
    if (wsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }

    if (width <= 0) {
        width = 1;
    }
    if (height <= 0) {
        height = 1;
    }

    wsdo->sdOps.Lock = WLSD_Lock;
    wsdo->sdOps.Unlock = WLSD_Unlock;
    wsdo->sdOps.GetRasInfo = WLSD_GetRasInfo;
    wsdo->sdOps.Dispose = WLSD_Dispose;
    wsdo->bufferManager = WLSBM_Create(width, height, backgroundRGB);
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    // Recursive mutex is required because blit can be done with both source
    // and destination being the same surface (during scrolling, for example).
    // So WLSD_Lock() should be able to lock the same surface twice in a row.
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE_NP);
    pthread_mutex_init(&wsdo->lock, &attr);
#endif /* !HEADLESS */
}
