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

jint MTLOptions_GlyphCacheSize(void) {
    static jint cache = -1;
    return textureSizeOption(@"sun.java2d.metal.glyphCacheSize", 0, &cache);
}

BOOL MTLOptions_GlyphCacheSync(void) {
    static int cache = -1;
    return boolOption(@"sun.java2d.metal.glyphCacheSync", YES, &cache);
}

BOOL MTLOptions_GpuOptimizedTextures(void) {
    static int cache = -1;
    return boolOption(@"sun.java2d.metal.gpuOptimizedTextures", YES, &cache);
}
