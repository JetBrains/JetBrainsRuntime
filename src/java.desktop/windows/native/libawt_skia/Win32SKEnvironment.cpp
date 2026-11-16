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
// renders through finds an adapter and its device starts, and what it is (Win32SKPlatform.h).
// The device is shared by the configurations; the Graphite contexts are created per
// configuration (SKGraphicsConfig.cpp).

#include "DawnSKPlatform.h"
#include "SKEntryLock.h"
#include "Win32SKPlatform.h"

#include <memory>
#include <string>
#include "jni.h"
#include "sun_java2d_skia_SKEnvironment.h"
#include "include/core/SkMilestone.h"

extern "C" {

// The graphics API the configurations will use, selected here: Dawn. The device is created now
// (and kept: the configurations share it), so an API that cannot start fails here, before any
// surface depends on it; null then, as when the API finds no adapter. Otherwise, what the backend
// runs on (SKEnvironment.runningOn): "Skia m<release> Graphite/<API> on <adapter>".
JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_startNative(JNIEnv *env, jclass)
{
    SK_ENTRY_LOCK;
    Win32SKGraphicsAPI &graphicsAPI = DawnSKPlatform::graphicsAPI();
    Win32SKPlatform::selectGraphicsAPI(&graphicsAPI);
    const std::unique_ptr<Win32SKConfigInfo> config(Win32SKPlatform::createConfig());
    if (config == nullptr) {
        return nullptr;
    }
    const std::string runningOn = std::string("Skia m") + std::to_string(SK_MILESTONE) + " Graphite/"
                                  + Win32SKPlatform::apiName(graphicsAPI.api()) + " on " + config->description();
    return env->NewStringUTF(runningOn.c_str());
}

// SKEnvironment.deviceFeatures: the features Graphite uses, offered and granted, of the selected API's
// last device (DawnSKPlatform.cpp).
JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_deviceFeaturesNative(JNIEnv *env, jclass)
{
    SK_ENTRY_LOCK;
    Win32SKGraphicsAPI *graphicsAPI = Win32SKPlatform::selectedGraphicsAPI();
    const std::string report = graphicsAPI != nullptr ? graphicsAPI->deviceFeatures() : std::string();
    return env->NewStringUTF(report.c_str());
}

} // extern "C"
