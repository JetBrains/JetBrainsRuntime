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

// Platform-independent part of sun.java2d.skia.SKEnvironment's native side: the configuration
// (SKConfiguration.h) and the counters the tests read. The start (startNative) and
// deviceFeaturesNative live with the platform (Win32SKEnvironment.cpp).

#include "jni.h"
#include "sun_java2d_skia_SKEnvironment.h"
#include "SKConfiguration.h"
#include "SKEntryLock.h"
#include "SKGlyphCache.h"

#include <array>

namespace {

// The counters as a Java long[], in their order; null if the array cannot be allocated (the
// pending OutOfMemoryError is the caller's).
template <size_t COUNT>
jlongArray toLongArray(JNIEnv *env, const std::array<jlong, COUNT> &values) {
    const jlongArray array = env->NewLongArray(static_cast<jsize>(COUNT));
    if (array != nullptr) {
        env->SetLongArrayRegion(array, 0, static_cast<jsize>(COUNT), values.data());
    }
    return array;
}

} // namespace

extern "C" {

JNIEXPORT jlongArray JNICALL
Java_sun_java2d_skia_SKEnvironment_glyphCacheCountersNative(JNIEnv *env, jclass)
{
    SK_ENTRY_LOCK;
    std::array<jlong, SK_GLYPH_CACHE_COUNTERS> values{};
    for (int counter = 0; counter < SK_GLYPH_CACHE_COUNTERS; counter++) {
        values[counter] = SKGlyphCache::counter(static_cast<SKGlyphCacheCounter>(counter));
    }
    return toLongArray(env, values);
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKEnvironment_configureNative(JNIEnv *, jclass, const jint glyphCacheTexels)
{
    SK_ENTRY_LOCK;
    SKConfiguration::configure(glyphCacheTexels);
}

} // extern "C"
