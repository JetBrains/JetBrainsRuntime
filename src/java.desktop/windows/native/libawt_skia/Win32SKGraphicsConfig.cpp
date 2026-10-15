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

// Native side of sun.java2d.skia.Win32SKGraphicsConfig: the Dawn device of a
// configuration (DawnSKConfigInfo, the SKPlatformConfig of this platform).

#include "DawnSKPlatform.h"

#include "jlong.h"
#include "jni.h"
#include "sun_java2d_skia_Win32SKGraphicsConfig.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_getConfigInfo(JNIEnv *env, jclass clazz, jint screen)
{
    // Dawn enumerates adapters itself; the screen is not needed to pick one
    // until multi-adapter setups are considered.
    return ptr_to_jlong(DawnSK_CreateConfig());
}

JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_getAdapterDescription(JNIEnv *env, jclass clazz, jlong pConfigInfo)
{
    auto *config = (DawnSKConfigInfo *) jlong_to_ptr(pConfigInfo);
    // adapter names are UTF-8 (Dawn's StringView), not the ANSI code page
    return config != nullptr ? env->NewStringUTF(config->description.c_str()) : nullptr;
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_isDeviceLost(JNIEnv *env, jclass clazz, jlong pConfigInfo)
{
    return DawnSK_IsLost((DawnSKConfigInfo *) jlong_to_ptr(pConfigInfo)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_disposeConfigInfo(JNIEnv *env, jclass clazz, jlong pConfigInfo)
{
    DawnSK_DisposeConfig((DawnSKConfigInfo *) jlong_to_ptr(pConfigInfo));
}

} // extern "C"
