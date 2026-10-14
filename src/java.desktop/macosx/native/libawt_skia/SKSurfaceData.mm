/*
 * Copyright 2026 JetBrains s.r.o.
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
// Native lifecycle of sun.java2d.skia.SKSurfaceData.

#include "SKBackend.h"

#include "jlong.h"
#include "jni_util.h"
#include "sun_java2d_skia_SKSurfaceData.h"

// The Skia surface has no software access path (like the Metal surfaces).
static jint SKSD_Lock(JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo, jint lockflags) {
    JNU_ThrowInternalError(env, "SKSD_Lock not implemented!");
    return SD_FAILURE;
}

static void SKSD_GetRasInfo(JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo) {
    JNU_ThrowInternalError(env, "SKSD_GetRasInfo not implemented!");
}

static void SKSD_Unlock(JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *pRasInfo) {
    JNU_ThrowInternalError(env, "SKSD_Unlock not implemented!");
}

// Invoked by the Disposer: releases the Skia wrapper on the flusher thread via
// the Java side, which enqueues DISPOSE_SURFACE (the Metal texture is owned and
// released by the wrapped MTLSurfaceData).
static void SKSD_Dispose(JNIEnv *env, SurfaceDataOps *ops) {
    JNU_CallStaticMethodByName(env, nullptr, "sun/java2d/skia/SKSurfaceData",
                               "dispose", "(J)V", ptr_to_jlong(ops));
}

extern "C" {

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKSurfaceData_initOps(JNIEnv *env, jobject skiasd,
                                             jlong mtlOps, jint type, jboolean isOpaque)
{
    SKSDOps *ops = (SKSDOps *) SurfaceData_InitOps(env, skiasd, sizeof(SKSDOps));
    if (ops == nullptr) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    ops->sdOps.Lock       = SKSD_Lock;
    ops->sdOps.GetRasInfo = SKSD_GetRasInfo;
    ops->sdOps.Unlock     = SKSD_Unlock;
    ops->sdOps.Dispose    = SKSD_Dispose;
    ops->mtl      = (BMTLSDOps *) jlong_to_ptr(mtlOps);
    ops->type     = type;
    ops->isOpaque = isOpaque;
    ops->state    = nullptr;
    J2dRlsTraceLn(J2D_TRACE_INFO, "SKSurfaceData_initOps: ops=%p mtl=%p type=%d", ops, ops->mtl, type);
}

} // extern "C"

// Whether these ops belong to a Skia surface (as opposed to a software raster).
bool SKSurfaceData_IsSkiaOps(SurfaceDataOps *ops) {
    return ops != nullptr && ops->Lock == SKSD_Lock;
}
