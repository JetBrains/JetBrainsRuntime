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

// Windows side of sun.java2d.skia.SKEnvironment: whether the graphics API Graphite
// renders through finds an adapter, and what it is (Win32SKPlatform.h). The device
// is shared by the configurations; the Graphite contexts are created per
// configuration (SKBackend.cpp).

#include "DawnSKPlatform.h"   // DawnSK_PreferBackend (sun.java2d.skia.dawn.backend)
#include "Win32SKPlatform.h"

#include <string>
#include <string.h>
#include "jni.h"
#include "sun_java2d_skia_SKEnvironment.h"
#include "include/core/SkMilestone.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_initNative(JNIEnv *env, jclass clazz)
{
    // sun.java2d.skia.dawn.backend=d3d12: D3D12 before D3D11 (read here, before the first adapter request)
    if (jclass system = env->FindClass("java/lang/System")) {
        jmethodID getProperty = env->GetStaticMethodID(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
        jstring key = env->NewStringUTF("sun.java2d.skia.dawn.backend");
        jstring value = getProperty != nullptr && key != nullptr
                ? (jstring) env->CallStaticObjectMethod(system, getProperty, key) : nullptr;
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            value = nullptr;
        }
        if (value != nullptr) {
            const char *chars = env->GetStringUTFChars(value, nullptr);
            DawnSK_PreferBackend(chars != nullptr && _stricmp(chars, "d3d12") == 0);
            if (chars != nullptr) {
                env->ReleaseStringUTFChars(value, chars);
            }
        }
    }
    std::string adapter = Win32SK_DescribeAdapter();
    if (adapter.empty()) {
        return nullptr;
    }
    // adapter names are UTF-8 (Dawn's StringView), not the ANSI code page
    std::string description = std::string("Skia m") + std::to_string(SK_MILESTONE) + " Graphite/Dawn on " + adapter;
    return env->NewStringUTF(description.c_str());
}

// Dawn reads the destination from a copy on every backend used here (Graphite's
// DstReadStrategy::kTextureCopy).
JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKEnvironment_framebufferFetchNative(JNIEnv *env, jclass clazz)
{
    return JNI_FALSE;
}

} // extern "C"
