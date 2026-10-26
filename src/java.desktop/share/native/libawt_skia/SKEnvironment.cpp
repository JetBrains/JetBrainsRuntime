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

// Platform-independent part of sun.java2d.skia.SKEnvironment's native side;
// initNative, gpuApiNative and framebufferFetchNative live with the platform
// (MTLSKEnvironment.mm on macOS, DawnSKEnvironment.cpp on Windows).

#include "jni.h"
#include "sun_java2d_skia_SKEnvironment.h"
#include "SKBackend.h"
#include "Trace.h"
#include "include/core/SkGraphics.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_SKEnvironment_glyphsDrawnBySkiaNative(JNIEnv *env, jclass clazz)
{
    return SKBackend_GlyphsDrawnBySkia();
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_SKEnvironment_glyphsShapedBySkiaNative(JNIEnv *env, jclass clazz)
{
    return SKBackend_GlyphsShapedBySkia();
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_SKEnvironment_paragraphsBySkiaNative(JNIEnv *env, jclass clazz)
{
    return SKBackend_ParagraphsBySkia();
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_SKEnvironment_flushesNative(JNIEnv *env, jclass clazz)
{
    return SKBackend_Flushes();
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKEnvironment_setStrikeCacheLimitNative(JNIEnv *env, jclass clazz, jlong bytes)
{
    const size_t previous = SkGraphics::SetFontCacheLimit((size_t) bytes);
    J2dRlsTraceLn(J2D_TRACE_INFO, "SKEnvironment: Skia strike cache limit %zu -> %lld bytes", previous, (long long) bytes);
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_SKEnvironment_strikeCacheLimitNative(JNIEnv *env, jclass clazz)
{
    return (jlong) SkGraphics::GetFontCacheLimit();
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_skia_SKEnvironment_glyphAtlasBudgetNative(JNIEnv *env, jclass clazz)
{
    return (jlong) SKBackend_GlyphAtlasBudget();
}

JNIEXPORT jint JNICALL
Java_sun_java2d_skia_SKEnvironment_glyphCacheSizeNative(JNIEnv *env, jclass clazz)
{
    return SKBackend_GlyphCacheTexels();
}

JNIEXPORT void JNICALL
Java_sun_java2d_skia_SKEnvironment_configureNative(JNIEnv *env, jclass clazz, jint msaaSamples, jboolean edgeAAQuads,
                                                   jint lcdMode, jboolean framebufferFetch,
                                                   jlong glyphAtlasBytes, jint glyphCacheTexels)
{
    SKBackend_Configure(msaaSamples, edgeAAQuads == JNI_TRUE, (SKLcdMode) lcdMode, framebufferFetch == JNI_TRUE,
                       (size_t) glyphAtlasBytes, glyphCacheTexels);
}

} // extern "C"
