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
// Native lifecycle of sun.java2d.skia.SKSurfaceData, and the Skia side of its texture (SKSurfaceState).

#include "SKSurfaceData.h"
#include "SKEntryLock.h"

#include "jlong.h"
#include "jni_util.h"
#include "Trace.h"
#include "sun_java2d_skia_SKSurfaceData.h"

#include "include/gpu/graphite/Surface.h"

namespace {

// The Skia surface has no software access path (like the Metal surfaces).
jint lockSurface(JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *rasterInfo, jint lockFlags) {
    JNU_ThrowInternalError(env, "lockSurface not implemented!");
    return SD_FAILURE;
}

void getRasInfo(JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *rasterInfo) {
    JNU_ThrowInternalError(env, "getRasInfo not implemented!");
}

void unlockSurface(JNIEnv *env, SurfaceDataOps *ops, SurfaceDataRasInfo *rasterInfo) {
    JNU_ThrowInternalError(env, "unlockSurface not implemented!");
}

// Invoked by the Disposer: releases the Skia wrapper on the flusher thread via
// the Java side, which enqueues DISPOSE_SURFACE (the texture is owned and
// released by the wrapped platform surface).
void disposeSurface(JNIEnv *env, SurfaceDataOps *ops) {
    JNU_CallStaticMethodByName(env, nullptr, "sun/java2d/skia/SKSurfaceData",
                               "dispose", "(J)V", ptr_to_jlong(ops));
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKSurfaceData_initOps(JNIEnv *env, jobject skiasd,
                                             jlong platformOps, jint type, jboolean isOpaque)
{
    SK_ENTRY_LOCK;
    SKSDOps *ops = (SKSDOps *) SurfaceData_InitOps(env, skiasd, sizeof(SKSDOps));
    if (ops == nullptr) {
        JNU_ThrowOutOfMemoryError(env, "Initialization of SurfaceData failed.");
        return;
    }
    ops->sdOps.Lock       = lockSurface;
    ops->sdOps.GetRasInfo = getRasInfo;
    ops->sdOps.Unlock     = unlockSurface;
    ops->sdOps.Dispose    = disposeSurface;
    ops->platform = (SKPlatformSurface *) jlong_to_ptr(platformOps);
    ops->type     = type;
    ops->isOpaque = isOpaque;
    ops->countFrames = JNI_FALSE;
    ops->state    = nullptr;
    J2dTraceLn(J2D_TRACE_VERBOSE, "SKSurfaceData_initOps: ops=%p platform=%p type=%d", ops, ops->platform, type);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKSurfaceData_setCountFrames(JNIEnv *env, jobject skiasd, jboolean countFrames)
{
    SK_ENTRY_LOCK;
    SKSDOps *ops = (SKSDOps *) SurfaceData_GetOps(env, skiasd);
    if (ops != nullptr) {
        ops->countFrames = countFrames;
    }
}

} // extern "C"

SKSurfaceState::SKSurfaceState() {
    static uint64_t surfaceUIDs = 0;   // under the entry mutex (SKEntryLock)
    m_uid = ++surfaceUIDs;
}

void SKSurfaceState::wrap(sk_sp<SkSurface> surface, void *texture, SKGraphicsConfig *graphicsConfig) {
    if (m_graphicsConfig != nullptr && m_graphicsConfig != graphicsConfig) {
        // never: a surface is wrapped with its own configuration's context, and leaves a context only by unwrap
        J2dRlsTraceLn(J2D_TRACE_ERROR, "SKSurfaceState::wrap: the surface is one of another context's surfaces");
    }
    m_surface = std::move(surface);
    m_wrappedTexture = texture;
    m_graphicsConfig = graphicsConfig;
}

void SKSurfaceState::unwrap() {
    m_surface.reset();
    m_wrappedTexture = nullptr;
    m_graphicsConfig = nullptr;
    m_presentTargets.clear();
}

sk_sp<SkSurface> SKSurfaceState::presentTarget(const SKPlatformPresentTarget &target) {
    if (!m_surface) {
        return nullptr;
    }
    const uint32_t generation = (uint32_t) (target.imageKey >> SK_IMAGE_KEY_GENERATION_SHIFT);
    if (generation != m_presentGeneration) {
        m_presentTargets.clear();   // the images of an earlier swap chain
        m_presentGeneration = generation;
    }
    sk_sp<SkSurface> &image = m_presentTargets[target.imageKey];
    if (!image) {
        image = SkSurfaces::WrapBackendTexture(m_surface->recorder(), target.texture, target.colorType, nullptr, nullptr);
    }
    return image;
}

bool SKSurfaceData::isSkiaOps(SurfaceDataOps *ops) {
    return ops != nullptr && ops->Lock == lockSurface;
}

SKPlatformSurface *SKSurfaceData::platformOf(SurfaceDataOps *ops) {
    return isSkiaOps(ops) ? ((SKSDOps *) ops)->platform : nullptr;
}
