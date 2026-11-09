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

// Windows side of sun.java2d.skia.SKGraphicsAPIFallback.startFirstUsableAPI: whether a graphics
// API Graphite renders through finds an adapter, and whether its device starts
// (Win32SKPlatform.h). The device is shared by the configurations; the Graphite
// contexts are created per configuration (SKGraphicsConfig.cpp).

#include "DawnSKPlatform.h"
#include "VKSKPlatform.h"
#include "Win32SKPlatform.h"

#include <string>
#include <string.h>
#include "jni.h"
#include "sun_java2d_skia_SKGraphicsAPIFallback.h"
#include "include/core/SkMilestone.h"

namespace {

// The value of a system property, empty if unset.
std::string systemProperty(JNIEnv *env, const char *name) {
    std::string result;
    jclass systemClass = env->FindClass("java/lang/System");
    if (systemClass == nullptr) {
        env->ExceptionClear();
        return result;
    }
    jmethodID getProperty = env->GetStaticMethodID(systemClass, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring key = env->NewStringUTF(name);
    jstring value = getProperty != nullptr && key != nullptr
            ? (jstring) env->CallStaticObjectMethod(systemClass, getProperty, key) : nullptr;
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

// The API a JNI entry receives: SKGPUList.GraphiteAPI's ordinal, in Win32SKAPI's order.
Win32SKGraphicsAPI &graphicsAPIOf(jint api) {
    return static_cast<Win32SKAPI>(api) == Win32SKAPI::Vulkan ? VKSKPlatform::graphicsAPI() : DawnSKPlatform::graphicsAPI();
}

} // namespace

extern "C" {

// SKGraphicsAPIFallback.startFirstUsableAPI tries the graphics APIs in order (Dawn, then Vulkan;
// graphite.api=vulkan: the other way round) and asks this first for each: the adapter the API
// would use, for the GPU list, without creating a device; null if it has none.
JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKGraphicsAPIFallback_adapterNative(JNIEnv *env, jclass clazz, jint api)
{
    // before the first adapter request: sun.java2d.skia.dawn.api=d3d12 for Direct3D 12 before 11
    const std::string dawnAPI = systemProperty(env, "sun.java2d.skia.dawn.api");
    if (!dawnAPI.empty()) {
        DawnSKPlatform::preferD3D12(_stricmp(dawnAPI.c_str(), "d3d12") == 0);
    }
    // for tests: the Vulkan device of the retry with the minimal set (SkiaVulkanFeaturesTest)
    VKSKPlatform::rejectPreferredDevice(
            _stricmp(systemProperty(env, "sun.java2d.skia.test.rejectVulkanPreferredDevice").c_str(), "true") == 0);
    const std::string adapterLine = Win32SKPlatform::adapterForGPUList(graphicsAPIOf(api));
    // adapter names are UTF-8 (Dawn's StringView, Vulkan's deviceName), not the ANSI code page
    return adapterLine.empty() ? nullptr : env->NewStringUTF(adapterLine.c_str());
}

// The API the GPU list allowed: the API of every later configuration. Its device is created now
// (and kept: the configurations share it), so an API that cannot start fails here, before any
// surface depends on it; null then, and SKGraphicsAPIFallback falls back to the next API. Otherwise
// what the backend runs on (SKEnvironment.runningOn): "Skia m<release> Graphite/<API> on <adapter>".
JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKGraphicsAPIFallback_startNative(JNIEnv *env, jclass clazz, jint api)
{
    Win32SKGraphicsAPI &graphicsAPI = graphicsAPIOf(api);
    Win32SKPlatform::selectGraphicsAPI(&graphicsAPI);
    Win32SKConfigInfo *config = Win32SKPlatform::createConfig();
    if (config == nullptr) {
        return nullptr;
    }
    Win32SKPlatform::disposeConfig(config);
    const std::string adapter = graphicsAPI.describeAdapter();
    if (adapter.empty()) {
        return nullptr;
    }
    const std::string runningOn = std::string("Skia m") + std::to_string(SK_MILESTONE) + " Graphite/"
                                  + Win32SKPlatform::apiName(graphicsAPI.api()) + " on " + adapter;
    return env->NewStringUTF(runningOn.c_str());
}

} // extern "C"
