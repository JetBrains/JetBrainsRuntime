/*
 * Copyright (c) 2011, 2012, Oracle and/or its affiliates. All rights reserved.
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

#include "jni.h"
#include "jni_util.h"
#import "JNIUtilities.h"
#import "ThreadUtilities.h"

#import <Cocoa/Cocoa.h>

#ifdef DEBUG
    #define LOG_PROP 1
#else
    #define LOG_PROP 0
#endif

#define DECLARE_BOOL_SYS_PROP_RETURN(dst_var, key, def) \
{ \
    static int dst_var = -1; \
    if (dst_var == -1) { \
        JNIEnv *env = [ThreadUtilities getJNIEnvUncached]; \
        if (env == NULL) return NO; \
        NSString* sysProp = [PropertiesUtilities javaSystemPropertyForKey:key withEnv:env]; \
        dst_var = (sysProp != nil) ? ([@"true" isCaseInsensitiveLike:sysProp] ? 1 : 0) : 0; \
        if (LOG_PROP) NSLog(@"%s[sys prop: %@]: %d", #dst_var, key, dst_var); \
    } \
    return (BOOL)dst_var; \
}

JNIEXPORT @interface PropertiesUtilities : NSObject

+ (NSString *) javaSystemPropertyForKey:(NSString *)key withEnv:(JNIEnv *)env;

@end
