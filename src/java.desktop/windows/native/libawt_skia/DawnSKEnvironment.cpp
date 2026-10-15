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

// Windows side of sun.java2d.skia.SKEnvironment: whether Dawn finds a
// Direct3D adapter and what it is. The Dawn device is shared by the
// configurations (DawnSKPlatform.cpp); the Graphite contexts are created per
// configuration (SKBackend.cpp).

#include "DawnSKPlatform.h"

#include <string>
#include "jni.h"
#include "sun_java2d_skia_SKEnvironment.h"
#include "include/core/SkMilestone.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_initNative(JNIEnv *env, jclass clazz)
{
    std::string adapter = DawnSK_DescribeAdapter();
    if (adapter.empty()) {
        return nullptr;
    }
    // adapter names are UTF-8 (Dawn's StringView), not the ANSI code page
    std::string description = std::string("Skia m") + std::to_string(SK_MILESTONE) + " Graphite/Dawn on " + adapter;
    return env->NewStringUTF(description.c_str());
}

JNIEXPORT jstring JNICALL
Java_sun_java2d_skia_SKEnvironment_gpuApiNative(JNIEnv *env, jclass clazz)
{
    return env->NewStringUTF("dawn");
}

} // extern "C"
