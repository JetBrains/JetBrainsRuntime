/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
#include "jni_util.h"
#include "WLSurfaceData.h"


/*
 * Class:     sun_java2d_wl_WLSurfaceData
 * Method:    initSurface
 * Signature: (IIIJ)V
 */
JNIEXPORT void JNICALL
Java_sun_java2d_wl_WLSurfaceData_initSurface(JNIEnv *env, jclass wsd,
                                               jint depth,
                                               jint width, jint height,
                                               jlong drawable)
{
#ifndef HEADLESS
    WLSDOps *xsdo = WLSurfaceData_GetOps(env, wsd);
    if (xsdo == NULL) {
        return;
    }
#endif /* !HEADLESS */
}

JNIEXPORT WLSDOps * JNICALL
WLSurfaceData_GetOps(JNIEnv *env, jobject sData)
{
#ifdef HEADLESS
    return NULL;
#else
    SurfaceDataOps *ops = SurfaceData_GetOps(env, sData);
    if (ops != NULL) {
        SurfaceData_ThrowInvalidPipeException(env, "not an X11 SurfaceData");
        ops = NULL;
    }
    return (WLSDOps *) ops;
#endif /* !HEADLESS */
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
    jboolean hasException;
    if (wsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    if (peer != NULL) {
        wsdo->wl_surface = JNU_CallMethodByName(env, &hasException, peer, "getWLSurface", "()J").j;
        if (hasException) {
            return;
        }
    } else {
        wsdo->wl_surface = 0;
    }
    wsdo->bgPixel = 0;
    wsdo->isBgInitialized = JNI_FALSE;

#endif /* !HEADLESS */
}