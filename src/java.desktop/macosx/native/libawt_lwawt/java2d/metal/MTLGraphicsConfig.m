/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#import "sun_java2d_metal_MTLGraphicsConfig.h"

#import "MTLGraphicsConfig.h"
#import "MTLSurfaceData.h"
#import "ThreadUtilities.h"
#import "awt.h"

#import <stdlib.h>
#import <string.h>
#import <ApplicationServices/ApplicationServices.h>
#import <JavaNativeFoundation/JavaNativeFoundation.h>

#pragma mark -
#pragma mark "--- Mac OS X specific methods for GL pipeline ---"

// Uncomment this line to see Metal specific fprintfs
//#define METAL_DEBUG

/**
 * Disposes all memory and resources associated with the given
 * CGLGraphicsConfigInfo (including its native MTLContext data).
 */
void
MTLGC_DestroyMTLGraphicsConfig(jlong pConfigInfo)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLGC_DestroyMTLGraphicsConfig");

    MTLGraphicsConfigInfo *mtlinfo =
        (MTLGraphicsConfigInfo *)jlong_to_ptr(pConfigInfo);
    if (mtlinfo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "MTLGC_DestroyMTLGraphicsConfig: info is null");
        return;
    }

    MTLContext *mtlc = (MTLContext*)mtlinfo->context;
    if (mtlc != NULL) {
        [mtlinfo->context release];
        mtlinfo->context = nil;
    }
    free(mtlinfo);
}

#pragma mark -
#pragma mark "--- MTLGraphicsConfig methods ---"


/**
 * Attempts to initialize CGL and the core OpenGL library.
 */
JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLGraphicsConfig_initMTL
    (JNIEnv *env, jclass cglgc)
{
    J2dRlsTraceLn(J2D_TRACE_INFO, "MTLGraphicsConfig_initMTL");

#ifdef METAL_DEBUG
    FILE *f = popen("/usr/sbin/system_profiler SPDisplaysDataType", "r");
    bool metalSupport = FALSE;
    while (getc(f) != EOF)
    {
        char str[60];

        if (fgets(str, 60, f) != NULL) {
            // Check for string
            // "Metal:  Supported, feature set macOS GPUFamily1 v4"
            if (strstr(str, "Metal") != NULL) {
                puts(str);
                metalSupport = JNI_TRUE;
                break;
            }
        }
    }
    pclose(f);
    if (!metalSupport) {
        fprintf(stderr, "Metal support not present\n");
    } else {
        fprintf(stderr, "Metal support is present\n");
    }
#endif

    if (!MTLFuncs_OpenLibrary()) {
        return JNI_FALSE;
    }

    if (!MTLFuncs_InitPlatformFuncs() ||
        !MTLFuncs_InitBaseFuncs() ||
        !MTLFuncs_InitExtFuncs())
    {
        MTLFuncs_CloseLibrary();
        return JNI_FALSE;
    }

    return JNI_TRUE;
}


/**
 * Determines whether the CGL pipeline can be used for a given GraphicsConfig
 * provided its screen number and visual ID.  If the minimum requirements are
 * met, the native CGLGraphicsConfigInfo structure is initialized for this
 * GraphicsConfig with the necessary information (pixel format, etc.)
 * and a pointer to this structure is returned as a jlong.  If
 * initialization fails at any point, zero is returned, indicating that CGL
 * cannot be used for this GraphicsConfig (we should fallback on an existing
 * 2D pipeline).
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_metal_MTLGraphicsConfig_getMTLConfigInfo
    (JNIEnv *env, jclass cglgc, jint displayID, jstring mtlShadersLib)
{
  jlong ret = 0L;
  JNF_COCOA_ENTER(env);
  NSMutableArray * retArray = [NSMutableArray arrayWithCapacity:3];
  [retArray addObject: [NSNumber numberWithInt: (int)displayID]];
  [retArray addObject: [NSString stringWithUTF8String: JNU_GetStringPlatformChars(env, mtlShadersLib, 0)]];
  if ([NSThread isMainThread]) {
      [MTLGraphicsConfigUtil _getMTLConfigInfo: retArray];
  } else {
      [MTLGraphicsConfigUtil performSelectorOnMainThread: @selector(_getMTLConfigInfo:) withObject: retArray waitUntilDone: YES];
  }
  NSNumber * num = (NSNumber *)[retArray objectAtIndex: 0];
  ret = (jlong)[num longValue];
  JNF_COCOA_EXIT(env);
  return ret;
}




@implementation MTLGraphicsConfigUtil
+ (void) _getMTLConfigInfo: (NSMutableArray *)argValue {
    AWT_ASSERT_APPKIT_THREAD;

    jint displayID = (jint)[(NSNumber *)[argValue objectAtIndex: 0] intValue];
    NSString *mtlShadersLib = (NSString *)[argValue objectAtIndex: 1];
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    [argValue removeAllObjects];

    J2dRlsTraceLn(J2D_TRACE_INFO, "MTLGraphicsConfig_getMTLConfigInfo");

    NSAutoreleasePool* pool = [[NSAutoreleasePool alloc] init];


    NSRect contentRect = NSMakeRect(0, 0, 64, 64);
    NSWindow *window =
        [[NSWindow alloc]
            initWithContentRect: contentRect
            styleMask: NSBorderlessWindowMask
            backing: NSBackingStoreBuffered
            defer: false];
    if (window == nil) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLGraphicsConfig_getMTLConfigInfo: NSWindow is NULL");
        [argValue addObject: [NSNumber numberWithLong: 0L]];
        return;
    }

    NSView *scratchSurface =
        [[NSView alloc]
            initWithFrame: contentRect];
    if (scratchSurface == nil) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLGraphicsConfig_getMTLConfigInfo: NSView is NULL");
        [argValue addObject: [NSNumber numberWithLong: 0L]];
        return;
    }
    [window setContentView: scratchSurface];

    MTLContext *mtlc = [[MTLContext alloc] initWithDevice:CGDirectDisplayCopyCurrentMetalDevice(displayID)
                        shadersLib:mtlShadersLib];
    if (mtlc == 0L) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLGC_InitMTLContext: could not allocate memory for mtlc");
        [argValue addObject: [NSNumber numberWithLong: 0L]];
        return;
    }


    // create the MTLGraphicsConfigInfo record for this config
    MTLGraphicsConfigInfo *mtlinfo = (MTLGraphicsConfigInfo *)malloc(sizeof(MTLGraphicsConfigInfo));
    if (mtlinfo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLGraphicsConfig_getMTLConfigInfo: could not allocate memory for mtlinfo");
        free(mtlc);
        [argValue addObject: [NSNumber numberWithLong: 0L]];
        return;
    }
    memset(mtlinfo, 0, sizeof(MTLGraphicsConfigInfo));
    mtlinfo->screen = displayID;
    mtlinfo->context = mtlc;

    [argValue addObject: [NSNumber numberWithLong:ptr_to_jlong(mtlinfo)]];
    [pool drain];
}
@end //GraphicsConfigUtil


JNIEXPORT jint JNICALL
Java_sun_java2d_metal_MTLGraphicsConfig_nativeGetMaxTextureSize
    (JNIEnv *env, jclass mtlgc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLGraphicsConfig_nativeGetMaxTextureSize");

    return (jint)MaxTextureSize;
}
