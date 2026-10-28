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

// The value of a system property, empty if unset.
static std::string systemProperty(JNIEnv *env, const char *name) {
    std::string result;
    jclass system = env->FindClass("java/lang/System");
    if (system == nullptr) {
        env->ExceptionClear();
        return result;
    }
    jmethodID getProperty = env->GetStaticMethodID(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring key = env->NewStringUTF(name);
    jstring value = getProperty != nullptr && key != nullptr
            ? (jstring) env->CallStaticObjectMethod(system, getProperty, key) : nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        value = nullptr;
    }
    if (value != nullptr) {
        const char *chars = env->GetStringUTFChars(value, nullptr);
        if (chars != nullptr) {
            result = chars;
            env->ReleaseStringUTFChars(value, chars);
        }
    }
    return result;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_initNative(JNIEnv *env, jclass clazz)
{
    // read here, before the first adapter request: sun.java2d.skia.graphiteApi=vulkan
    // for native Vulkan (other values leave Dawn, SKEnvironment.checkGraphiteApi), and
    // sun.java2d.skia.dawn.backend=d3d12 for D3D12 before D3D11
    if (_stricmp(systemProperty(env, "sun.java2d.skia.graphiteApi").c_str(), "vulkan") == 0) {
        Win32SK_SelectApi(Win32SKApi::Vulkan);
    }
    const std::string dawnBackend = systemProperty(env, "sun.java2d.skia.dawn.backend");
    if (!dawnBackend.empty()) {
        DawnSK_PreferBackend(_stricmp(dawnBackend.c_str(), "d3d12") == 0);
    }
    std::string adapter = Win32SK_DescribeAdapter();
    if (adapter.empty()) {
        return nullptr;
    }
    // adapter names are UTF-8 (Dawn's StringView, Vulkan's deviceName), not the ANSI code page
    std::string description = std::string("Skia m") + std::to_string(SK_MILESTONE) + " Graphite/"
                              + Win32SK_ApiName(Win32SK_SelectedApi()) + " on " + adapter;
    return env->NewStringUTF(description.c_str());
}

// Dawn reads the destination from a copy on every backend used here (Graphite's
// DstReadStrategy::kTextureCopy). Graphite's Vulkan backend may read it as an input
// attachment instead; until that is measured, Vulkan keeps Dawn's defaults.
JNIEXPORT jboolean JNICALL
Java_sun_java2d_skia_SKEnvironment_framebufferFetchNative(JNIEnv *env, jclass clazz)
{
    return JNI_FALSE;
}

} // extern "C"
