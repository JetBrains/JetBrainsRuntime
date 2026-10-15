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

// Native side of sun.java2d.skia.Win32SKSurfaceData: the Dawn texture (and
// swap chain) of a surface (DawnSKSurface, the SKPlatformSurface of this
// platform). It is released by SKPlatform_DisposeSurface when the Skia wrapper
// is disposed.

#include "DawnSKPlatform.h"
#include "SKBackend.h"   // SKSurfaceData_PlatformOf

#include "jlong.h"
#include "jni.h"
#include "sun_java2d_skia_Win32SKSurfaceData.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_Win32SKSurfaceData_createPlatformSurface(JNIEnv *env, jclass clazz, jlong pConfigInfo,
                                                              jlong hwnd, jint width, jint height,
                                                              jlong previousOps, jboolean isOpaque)
{
    auto *config = (DawnSKConfigInfo *) jlong_to_ptr(pConfigInfo);
    if (config == nullptr || width <= 0 || height <= 0) {
        return 0;
    }
    // the window surface being replaced, unless its Skia side is disposed already
    auto *previousSurface = (DawnSKSurface *) SKSurfaceData_PlatformOf((SurfaceDataOps *) jlong_to_ptr(previousOps));
    return ptr_to_jlong(DawnSK_CreateSurface(config, (HWND) jlong_to_ptr(hwnd), (uint32_t) width,
                                             (uint32_t) height, previousSurface, isOpaque == JNI_TRUE));
}

} // extern "C"
