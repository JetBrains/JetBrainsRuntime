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

#include <unordered_map>

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
// the Java side, which enqueues DISPOSE_SURFACE (the texture is owned and
// released by the wrapped platform surface).
static void SKSD_Dispose(JNIEnv *env, SurfaceDataOps *ops) {
    JNU_CallStaticMethodByName(env, nullptr, "sun/java2d/skia/SKSurfaceData",
                               "dispose", "(J)V", ptr_to_jlong(ops));
}

#ifdef __APPLE__
// The platform surfaces Skia surfaces wrap (the Metal delegates: they own their native ops and have a Disposer record
// of their own), each with the Skia ops wrapping it; guarded by the entry lock. A delegate's native Dispose is hooked
// (SKSD_DisposePlatform) so that the Skia side finishes with the platform surface before Metal frees it, whichever of
// the two records runs first (SKSurfaceData.disposePlatform).
static std::unordered_map<SKPlatformSurface *, SKSDOps *> gWrapped;
static DisposeFunc *gPlatformDispose = nullptr;   // MTLSD_Dispose, every Metal surface's

static void SKSD_DisposePlatform(JNIEnv *env, SurfaceDataOps *platform) {
    JNU_CallStaticMethodByName(env, nullptr, "sun/java2d/skia/SKSurfaceData",
                               "disposePlatform", "(J)V", ptr_to_jlong(platform));
    if (gPlatformDispose != nullptr) {
        gPlatformDispose(env, platform);
    }
}
#endif

void SKSurfaceData_ForgetWrapper(SKSDOps *ops) {
#ifdef __APPLE__
    for (auto it = gWrapped.begin(); it != gWrapped.end(); ++it) {
        if (it->second == ops) {
            gWrapped.erase(it);
            return;
        }
    }
#endif
}

extern "C" {

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKSurfaceData_initOps(JNIEnv *env, jobject skiasd,
                                             jlong platformOps, jint type, jboolean isOpaque)
{
    SK_ENTRY_LOCK;   // the flusher may be running a handed-off flush (SKBackend_EntryMutex)
    SKSDOps *ops = (SKSDOps *) SurfaceData_InitOps(env, skiasd, sizeof(SKSDOps));
    if (ops == nullptr) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    ops->sdOps.Lock       = SKSD_Lock;
    ops->sdOps.GetRasInfo = SKSD_GetRasInfo;
    ops->sdOps.Unlock     = SKSD_Unlock;
    ops->sdOps.Dispose    = SKSD_Dispose;
    ops->platform = (SKPlatformSurface *) jlong_to_ptr(platformOps);
    ops->type     = type;
    ops->isOpaque = isOpaque;
    ops->countFrames = JNI_FALSE;
    ops->state    = nullptr;
#ifdef __APPLE__
    if (ops->platform != nullptr) {
        SurfaceDataOps *platform = (SurfaceDataOps *) ops->platform;   // BMTLSDOps: its SurfaceDataOps first
        if (platform->Dispose != SKSD_DisposePlatform) {
            if (gPlatformDispose == nullptr) {
                gPlatformDispose = platform->Dispose;
            }
            if (platform->Dispose == gPlatformDispose) {
                platform->Dispose = SKSD_DisposePlatform;
            }
        }
        gWrapped[ops->platform] = ops;
    }
#endif
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKSurfaceData_initOps: ops=%p platform=%p type=%d", ops, ops->platform, type);
}

// SKSurfaceData.disposePlatform, the queue flushed: the Skia surface wrapping the platform surface (if it is not
// disposed yet) releases what it holds of it and forgets it; its own disposal follows later.
JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKSurfaceData_forgetPlatform(JNIEnv *env, jclass clazz, jlong platformOps)
{
#ifdef __APPLE__
    SK_ENTRY_LOCK;   // the flusher may be running a handed-off flush (SKBackend_EntryMutex)
    SKPlatformScope scope;
    auto it = gWrapped.find((SKPlatformSurface *) jlong_to_ptr(platformOps));
    if (it == gWrapped.end()) {
        return;
    }
    SKSDOps *ops = it->second;
    gWrapped.erase(it);
    if (ops == SKBackend_CurrentDestination()) {
        SKBackend_InvalidateState();
    }
    SKBackend_ReleaseSurface(ops, false);
    ops->platform = nullptr;
#endif
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKSurfaceData_setCountFrames(JNIEnv *env, jobject skiasd, jboolean countFrames)
{
    SK_ENTRY_LOCK;   // the flusher may be running a handed-off flush (SKBackend_EntryMutex)
    SKSDOps *ops = (SKSDOps *) SurfaceData_GetOps(env, skiasd);
    if (ops != nullptr) {
        ops->countFrames = countFrames;
    }
}

} // extern "C"

// Whether these ops belong to a Skia surface (as opposed to a software raster).
bool SKSurfaceData_IsSkiaOps(SurfaceDataOps *ops) {
    return ops != nullptr && ops->Lock == SKSD_Lock;
}

SKPlatformSurface *SKSurfaceData_PlatformOf(SurfaceDataOps *ops) {
    return SKSurfaceData_IsSkiaOps(ops) ? ((SKSDOps *) ops)->platform : nullptr;
}
