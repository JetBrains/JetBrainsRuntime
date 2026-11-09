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

// Native side of sun.java2d.skia.Win32SKGraphicsConfig: the GPU device of a
// configuration (Win32SKConfigInfo, the SKPlatformConfig of this platform), whichever
// graphics API Graphite renders through (Win32SKPlatform.h).

#include "SKEntryLock.h"
#include "Win32SKPlatform.h"

#include "jlong.h"
#include "jni.h"
#include "sun_java2d_skia_Win32SKGraphicsConfig.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_getConfigInfo(JNIEnv *env, jclass clazz, jint screen)
{
    SK_ENTRY_LOCK;
    // the API enumerates adapters itself; the screen is not needed to pick one
    // until multi-adapter setups are considered
    return ptr_to_jlong(Win32SKPlatform::createConfig());
}

JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_getAdapterDescription(JNIEnv *env, jclass clazz, jlong pConfigInfo)
{
    SK_ENTRY_LOCK;
    auto *config = (Win32SKConfigInfo *) jlong_to_ptr(pConfigInfo);
    // adapter names are UTF-8, not the ANSI code page
    return config != nullptr ? env->NewStringUTF(Win32SKPlatform::configDescription(config).c_str()) : nullptr;
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_isDeviceLost(JNIEnv *env, jclass clazz, jlong pConfigInfo)
{
    // Without the entry lock: a flush holds it from start to end, and this is asked on the event
    // thread for every validation (VolatileImage.validate, the proxies). It reads the device's
    // lost flag, an atomic; the configuration's device pointer is set once at creation, and the
    // configuration is freed only by the Disposer after the Java configuration that calls this
    // is unreachable (Win32SKGraphicsConfig.isDeviceLost keeps it reachable for the call).
    return Win32SKPlatform::isLost((Win32SKConfigInfo *) jlong_to_ptr(pConfigInfo)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_simulateDeviceLossNative(JNIEnv *env, jclass clazz, jboolean persistent)
{
    SK_ENTRY_LOCK;
    Win32SKPlatform::simulateDeviceLoss(persistent == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_Win32SKGraphicsConfig_disposeConfigInfo(JNIEnv *env, jclass clazz, jlong pConfigInfo)
{
    SK_ENTRY_LOCK;
    Win32SKPlatform::disposeConfig((Win32SKConfigInfo *) jlong_to_ptr(pConfigInfo));
}

} // extern "C"
