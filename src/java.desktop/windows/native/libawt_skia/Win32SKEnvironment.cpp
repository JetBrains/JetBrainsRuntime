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

// Windows side of sun.java2d.skia.SKEnvironment: what the GPU affords the configuration
// (framebufferFetchNative) and the device features for the tests. The start is
// SKGraphicsAPIFallback's (Win32SKGraphicsAPIFallback.cpp).

#include "Win32SKPlatform.h"

#include <string>
#include "jni.h"
#include "sun_java2d_skia_SKEnvironment.h"

extern "C" {

// Dawn reads the destination from a copy on Direct3D 11 and 12 alike (Graphite's
// DstReadStrategy::kTextureCopy). Graphite's Vulkan backend may read it as an input
// attachment instead; until that is measured, Vulkan keeps Dawn's defaults.
JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKEnvironment_framebufferFetchNative(JNIEnv *env, jclass clazz)
{
    return JNI_FALSE;
}

// SKEnvironment.deviceFeatures: the features Graphite uses, offered and granted, of the selected API's
// last device (DawnSKPlatform.cpp, VKSKPlatform.cpp).
JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_deviceFeaturesNative(JNIEnv *env, jclass clazz)
{
    Win32SKGraphicsAPI *graphicsAPI = Win32SKPlatform::selectedGraphicsAPI();
    const std::string report = graphicsAPI != nullptr ? graphicsAPI->deviceFeatures() : std::string();
    return env->NewStringUTF(report.c_str());
}

} // extern "C"
