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

#import "MTLOptions.h"
#import "PropertiesUtilities.h"
#import "ThreadUtilities.h"
#import "jni_util.h"
#include "Trace.h"

static NSString *propertyValue(NSString *key, JNIEnv *env) {
    return [PropertiesUtilities javaSystemPropertyForKey:key withEnv:env];
}

// -1 = not read yet; defaults are returned uncached if the property cannot be read.
static BOOL boolOption(NSString *key, BOOL defaultValue, int *cache) {
    if (*cache == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) {
            return defaultValue;
        }
        NSString *value = propertyValue(key, env);
        *cache = value == nil ? defaultValue
               : [@"true" isCaseInsensitiveLike:value] ? YES
               : [@"false" isCaseInsensitiveLike:value] ? NO : defaultValue;
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLOptions: %s = %d", [key UTF8String], *cache);
    }
    return (BOOL) *cache;
}

// A texture side: a multiple of 32 between 32 and 16384; anything else is the default.
static jint textureSizeOption(NSString *key, jint defaultValue, jint *cache) {
    if (*cache == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) {
            return defaultValue;
        }
        NSString *value = propertyValue(key, env);
        const jint size = value != nil ? [value intValue] : 0;
        *cache = size >= 32 && size <= 16384 && size % 32 == 0 ? size : defaultValue;
        J2dRlsTraceLn(J2D_TRACE_INFO, "MTLOptions: %s = %d", [key UTF8String], *cache);
    }
    return *cache;
}

BOOL MTLOptions_CoalesceCommits(void) {
    static int cache = -1;
    return boolOption(@"sun.java2d.metal.coalesceCommits", NO, &cache);
}

// 512 texels (256 cells of 32x32) as the pipeline always had; 2048 (4096 cells) when
// glyphs are rasterized per text luminance class (FontUtilities.textLuminanceDependent,
// the cg route of the Skia scaler): one strike per class multiplies the glyph set, and
// 16 classes of one font thrash 256 cells (a frame of 3000 strings in 16 colours took
// 176 ms against 10 ms with 2048, ledger §19b.1).
static jint glyphCacheDefault(JNIEnv *env) {
    jboolean exception = JNI_FALSE;
    const jvalue luminance = JNU_GetStaticFieldByName(env, &exception, "sun/font/FontUtilities", "textLuminanceDependent", "Z");
    if (exception) {
        (*env)->ExceptionClear(env);
        return 512;
    }
    return luminance.z ? 2048 : 512;
}

jint MTLOptions_GlyphCacheSize(void) {
    static jint cache = -1;
    if (cache == -1) {
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
        if (env == NULL) {
            return 512;
        }
        jint scratch = -1;
        cache = textureSizeOption(@"sun.java2d.metal.glyphCacheSize", glyphCacheDefault(env), &scratch);
    }
    return cache;
}

BOOL MTLOptions_GlyphCacheSync(void) {
    static int cache = -1;
    return boolOption(@"sun.java2d.metal.glyphCacheSync", YES, &cache);
}

BOOL MTLOptions_GpuOptimizedTextures(void) {
    static int cache = -1;
    return boolOption(@"sun.java2d.metal.gpuOptimizedTextures", YES, &cache);
}

BOOL MTLOptions_TextGammaCorrection(void) {
    static int cache = -1;
    return boolOption(@"sun.java2d.metal.textGammaCorrection", YES, &cache);
}
