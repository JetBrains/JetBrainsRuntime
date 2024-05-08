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
#include "SurfaceData.h"

#include "awt.h"
#include "Trace.h"
#include "WLSMSurfaceData.h"
#include "WLBuffers.h"
#include "WLToolkit.h"

struct WLSDOps {
    SurfaceDataOps      sdOps;
    WLSurfaceBufferManager * bufferManager;
    pthread_mutex_t     lock;
};

static void
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

static jmethodID countNewFrameMID;
static jmethodID countDroppedFrameMID;

JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSMSurfaceData_initIDs
        (JNIEnv *env, jclass clazz)
{
    // NB: don't care if those "count" methods are found.
    countNewFrameMID = (*env)->GetMethodID(env, clazz, "countNewFrame", "()V");
    countDroppedFrameMID = (*env)->GetMethodID(env, clazz, "countDroppedFrame", "()V");
}

JNIEXPORT WLSDOps * JNICALL
WLSMSurfaceData_GetOps(JNIEnv *env, jobject sData)
{
#ifdef HEADLESS
    return NULL;
#else
    SurfaceDataOps *ops = SurfaceData_GetOps(env, sData);
    if (ops == NULL) {
        SurfaceData_ThrowInvalidPipeException(env, "not a valid WLSMSurfaceData");
    }
    return (WLSDOps *) ops;
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSMSurfaceData_assignSurface(JNIEnv *env, jobject wsd,
                                                 jlong wlSurfacePtr)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLSMSurfaceData_assignSurface\n");
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }

    WLSBM_SurfaceAssign(wsdo->bufferManager, (struct wl_surface*)jlong_to_ptr(wlSurfacePtr));
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSMSurfaceData_flush(JNIEnv *env, jobject wsd)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLSMSurfaceData_flush\n");
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }

    WLSBM_SurfaceCommit(wsdo->bufferManager);
#endif /* !HEADLESS */
}

JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSMSurfaceData_revalidate(JNIEnv *env, jobject wsd,
                                              jint width, jint height, jint scale)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "WLSMSurfaceData_revalidate to size %d x %d\n", width, height);
    WLSDOps *wsdo = (WLSDOps*)SurfaceData_GetOps(env, wsd);
    if (wsdo == NULL) {
        return;
    }

    WLSBM_SizeChangeTo(wsdo->bufferManager, width, height);
#endif /* !HEADLESS */
}

JNIEXPORT jint JNICALL
Java_sun_java2d_wl_WLSMSurfaceData_pixelAt(JNIEnv *env, jobject wsd, jint x, jint y)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "Java_sun_java2d_wl_WLSMSurfaceData_pixelAt\n");
    int pixel = 0xFFB6C1; // the color pink to make errors visible

    SurfaceDataOps *ops = SurfaceData_GetOps(env, wsd);
    JNU_CHECK_EXCEPTION_RETURN(env, pixel);
    if (ops == NULL) {
        return pixel;
    }

    SurfaceDataRasInfo rasInfo = {.bounds = {x, y, x + 1, y + 1}};
    if (ops->Lock(env, ops, &rasInfo, SD_LOCK_READ)) {
        JNU_ThrowByName(env, "java/lang/ArrayIndexOutOfBoundsException", "Coordinate out of bounds");
        return pixel;
    }

    ops->GetRasInfo(env, ops, &rasInfo);
    if (rasInfo.rasBase) {
        unsigned char *pixelPtr =
                (unsigned char *) rasInfo.rasBase
                + (x * rasInfo.pixelStride + y * rasInfo.scanStride);
        if (rasInfo.pixelStride == 4) {
            // We don't have any other pixel sizes at the moment,
            // but this check will future-proof the code somewhat
            pixel = *(int *) pixelPtr;
        }
    }
    SurfaceData_InvokeRelease(env, ops, &rasInfo);
    SurfaceData_InvokeUnlock(env, ops, &rasInfo);

    return pixel;
#endif /* !HEADLESS */
}

JNIEXPORT jarray JNICALL
Java_sun_java2d_wl_WLSMSurfaceData_pixelsAt(JNIEnv *env, jobject wsd, jint x, jint y, jint width, jint height)
{
#ifndef HEADLESS
    J2dTrace(J2D_TRACE_INFO, "Java_sun_java2d_wl_WLSMSurfaceData_pixelAt\n");

    SurfaceDataOps *ops = SurfaceData_GetOps(env, wsd);
    JNU_CHECK_EXCEPTION_RETURN(env, NULL);
    if (ops == NULL) {
        return NULL;
    }

    SurfaceDataRasInfo rasInfo = {.bounds = {x, y, x + width, y + height}};
    if (ops->Lock(env, ops, &rasInfo, SD_LOCK_READ)) {
        JNU_ThrowByName(env, "java/lang/ArrayIndexOutOfBoundsException", "Coordinate out of bounds");
        return NULL;
    }

    if (rasInfo.bounds.x2 - rasInfo.bounds.x1 < width || rasInfo.bounds.y2 - rasInfo.bounds.y1 < height) {
        SurfaceData_InvokeUnlock(env, ops, &rasInfo);
        JNU_ThrowByName(env, "java/lang/ArrayIndexOutOfBoundsException", "Surface too small");
        return NULL;
    }

    jintArray arrayObj = NULL;
    ops->GetRasInfo(env, ops, &rasInfo);
    if (rasInfo.rasBase && rasInfo.pixelStride == sizeof(jint)) {
        size_t bufferSizeInPixels = width * height;
        arrayObj = (*env)->NewIntArray(env, bufferSizeInPixels);
        if (arrayObj != NULL) {
            jint *array = (*env)->GetPrimitiveArrayCritical(env, arrayObj, NULL);
            if (array == NULL) {
                JNU_ThrowOutOfMemoryError(env, "Wayland window pixels capture");
            } else {
                for (int i = y; i < y + height; i += 1) {
                    jint *destRow = &array[(i - y) * width];
                    jint *srcRow = (int*)((unsigned char *) rasInfo.rasBase + i * rasInfo.scanStride);
                    for (int j = x; j < x + width; j += 1) {
                        destRow[j - x] = srcRow[j];
                    }
                }
                (*env)->ReleasePrimitiveArrayCritical(env, arrayObj, array, 0);
            }
        }
    }
    SurfaceData_InvokeRelease(env, ops, &rasInfo);
    SurfaceData_InvokeUnlock(env, ops, &rasInfo);

    return arrayObj;
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

    J2dTrace(J2D_TRACE_INFO, "WLSD_Lock() at %d, %d for %dx%d\n",
             pRasInfo->bounds.x1, pRasInfo->bounds.y1,
             pRasInfo->bounds.x2 - pRasInfo->bounds.x1,
             pRasInfo->bounds.y2 - pRasInfo->bounds.y1);
    SurfaceData_IntersectBoundsXYWH(&pRasInfo->bounds,
                                    0,
                                    0,
                                    WLSBM_WidthGet(wlso->bufferManager),
                                    WLSBM_HeightGet(wlso->bufferManager));
    if (pRasInfo->bounds.x2 <= pRasInfo->bounds.x1 || pRasInfo->bounds.y2 <= pRasInfo->bounds.y1) {
        pthread_mutex_unlock(&wlso->lock);
        return SD_FAILURE;
    }
    WLSDPrivate *priv = (WLSDPrivate *) &(pRasInfo->priv);
    priv->lockFlags = lockflags;
    priv->wlBuffer = WLSBM_BufferAcquireForDrawing(wlso->bufferManager);
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

static void
CountFrameSent(jobject surfaceDataWeakRef)
{
    if (countNewFrameMID != NULL) {
        JNIEnv *env = getEnv();
        const jobject surfaceData = (*env)->NewLocalRef(env, surfaceDataWeakRef);
        if (surfaceData != NULL) {
            (*env)->CallVoidMethod(env, surfaceData, countNewFrameMID);
            (*env)->DeleteLocalRef(env, surfaceData);
            JNU_CHECK_EXCEPTION(env);
        }
    }
}

static void
CountFrameDropped(jobject surfaceDataWeakRef)
{
    if (countDroppedFrameMID != NULL) {
        JNIEnv *env = getEnv();
        const jobject surfaceData = (*env)->NewLocalRef(env, surfaceDataWeakRef);
        if (surfaceData != NULL) {
            (*env)->CallVoidMethod(env, surfaceData, countDroppedFrameMID);
            (*env)->DeleteLocalRef(env, surfaceData);
            JNU_CHECK_EXCEPTION(env);
        }
    }
}

/*
 * Class:     sun_java2d_wl_WLSMSurfaceData
 * Method:    initOps
 * Signature: (Ljava/lang/Object;Ljava/lang/Object;I)V
 */
JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSMSurfaceData_initOps(JNIEnv *env, jobject wsd,
                                           jint width,
                                           jint height,
                                           jint backgroundRGB,
                                           jint wlShmFormat,
                                           jboolean perfCountersEnabled)
{
#ifndef HEADLESS

    WLSDOps *wsdo = (WLSDOps*)SurfaceData_InitOps(env, wsd, sizeof(WLSDOps));
    J2dTrace(J2D_TRACE_INFO, "WLSMSurfaceData_initOps: %p\n", wsdo);
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

    jobject surfaceDataWeakRef = NULL;
    surfaceDataWeakRef = (*env)->NewWeakGlobalRef(env, wsd);
    JNU_CHECK_EXCEPTION(env);

    wsdo->sdOps.Lock = WLSD_Lock;
    wsdo->sdOps.Unlock = WLSD_Unlock;
    wsdo->sdOps.GetRasInfo = WLSD_GetRasInfo;
    wsdo->sdOps.Dispose = WLSD_Dispose;
    wsdo->bufferManager = WLSBM_Create(width, height, backgroundRGB, wlShmFormat,
                                       surfaceDataWeakRef,
                                       perfCountersEnabled ? CountFrameSent : NULL,
                                       perfCountersEnabled ? CountFrameDropped : NULL);
    if (wsdo->bufferManager == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Failed to create Wayland surface buffer manager");
        return;
    }
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    // Recursive mutex is required because blit can be done with both source
    // and destination being the same surface (during scrolling, for example).
    // So WLSD_Lock() should be able to lock the same surface twice in a row.
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&wsdo->lock, &attr);
#endif /* !HEADLESS */
}
