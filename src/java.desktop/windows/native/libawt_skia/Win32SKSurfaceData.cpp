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

// Native side of sun.java2d.skia.Win32SKSurfaceData: the texture (and swap
// chain) of a surface (Win32SKSurface, the SKPlatformSurface of this platform),
// whichever graphics API Graphite renders through (Win32SKPlatform.h). Java hands the
// address of its SKPlatformSurface to the surface's ops, whose disposal deletes it.

#include "Win32SKPlatform.h"
#include "SKEntryLock.h"
#include "SKSurfaceData.h"   // SKSurfaceDataOps

#include "jlong.h"
#include "jni.h"
#include "sun_java2d_skia_Win32SKSurfaceData.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_Win32SKSurfaceData_createPlatformSurface(JNIEnv *, jclass, const jlong pConfigInfo,
                                                              const jlong hwnd, const jint width, const jint height,
                                                              const jlong previousOps, const jboolean isOpaque)
{
    SK_ENTRY_LOCK;
    auto *platformConfig = static_cast<SKPlatformConfig *>(jlong_to_ptr(pConfigInfo));
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): all are Win32SKConfigInfo (Win32SKPlatform.h)
    auto *config = static_cast<Win32SKConfigInfo *>(platformConfig);
    if (config == nullptr || width <= 0 || height <= 0) {
        return 0;
    }
    // the window surface being replaced, unless its Skia side is disposed already
    const SKSurfaceDataOps *previous = SKSurfaceDataOps::from(static_cast<SurfaceDataOps *>(jlong_to_ptr(previousOps)));
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): all are Win32SKSurface (Win32SKPlatform.h)
    auto *previousSurface = previous != nullptr ? static_cast<Win32SKSurface *>(previous->platform) : nullptr;
    SKPlatformSurface *surface = config->createSurface(static_cast<HWND>(jlong_to_ptr(hwnd)),
                                                       static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                                       previousSurface, isOpaque == JNI_TRUE);
    return ptr_to_jlong(surface);
}

} // extern "C"
