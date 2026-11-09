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

// Windows side of sun.java2d.skia.SKEnvironment.start: whether the graphics API Graphite
// renders through finds an adapter, and what it is (Win32SKPlatform.h). The device is
// shared by the configurations; the Graphite contexts are created per configuration
// (SKGraphicsConfig.cpp).

#include "DawnSKPlatform.h"
#include "VKSKPlatform.h"
#include "Win32SKPlatform.h"

#include <string>
#include <string.h>
#include "jni.h"
#include "sun_java2d_skia_SKEnvironment.h"
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

} // namespace

extern "C" {

// The graphics API the configurations will use, selected here from sun.java2d.skia.graphite.api
// (vulkan for native Vulkan, anything else leaves Dawn; SKEnvironment reports an unknown value),
// and sun.java2d.skia.dawn.api=d3d12 for Direct3D 12 before 11. The device is created by the
// first configuration; null if the API finds no adapter. Otherwise what the backend runs on
// (SKEnvironment.runningOn): "Skia m<release> Graphite/<API> on <adapter>".
JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_startNative(JNIEnv *env, jclass clazz)
{
    Win32SKGraphicsAPI &graphicsAPI = _stricmp(systemProperty(env, "sun.java2d.skia.graphite.api").c_str(), "vulkan") == 0
                                      ? VKSKPlatform::graphicsAPI() : DawnSKPlatform::graphicsAPI();
    Win32SKPlatform::selectGraphicsAPI(&graphicsAPI);
    const std::string dawnAPI = systemProperty(env, "sun.java2d.skia.dawn.api");
    if (!dawnAPI.empty()) {
        DawnSKPlatform::preferD3D12(_stricmp(dawnAPI.c_str(), "d3d12") == 0);
    }
    // for tests: the Vulkan device of the retry with the minimal set (SkiaVulkanFeaturesTest)
    VKSKPlatform::rejectPreferredDevice(
            _stricmp(systemProperty(env, "sun.java2d.skia.test.rejectVulkanPreferredDevice").c_str(), "true") == 0);
    const std::string adapter = graphicsAPI.describeAdapter();
    if (adapter.empty()) {
        return nullptr;
    }
    const std::string runningOn = std::string("Skia m") + std::to_string(SK_MILESTONE) + " Graphite/"
                                  + Win32SKPlatform::apiName(graphicsAPI.api()) + " on " + adapter;
    return env->NewStringUTF(runningOn.c_str());
}

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
