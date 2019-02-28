/*
 * Copyright 2018 JetBrains s.r.o.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

#import <stdlib.h>
#import <string.h>
#import <ApplicationServices/ApplicationServices.h>
#import <JavaNativeFoundation/JavaNativeFoundation.h>

#pragma mark -
#pragma mark "--- Mac OS X specific methods for GL pipeline ---"

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
        MTLContext_DestroyContextResources(mtlc);
        mtlinfo->context = NULL;
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


static struct TxtVertex verts[PGRAM_VERTEX_COUNT] = {
    {{-1.0, 1.0, 0.0}, {0.0, 0.0}},
    {{1.0, 1.0, 0.0}, {1.0, 0.0}},
    {{1.0, -1.0, 0.0}, {1.0, 1.0}},
    {{1.0, -1.0, 0.0}, {1.0, 1.0}},
    {{-1.0, -1.0, 0.0}, {0.0, 1.0}},
    {{-1.0, 1.0, 0.0}, {0.0, 0.0}}
};


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

    jint caps = CAPS_EMPTY;
    MTLContext_GetExtensionInfo(env, &caps);

    caps |= CAPS_DOUBLEBUFFERED;

    J2dRlsTraceLn1(J2D_TRACE_INFO,
                   "MTLGraphicsConfig_getMTLConfigInfo: db=%d",
                   (caps & CAPS_DOUBLEBUFFERED) != 0);


    MTLContext *mtlc = (MTLContext *)malloc(sizeof(MTLContext));
    if (mtlc == 0L) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLGC_InitMTLContext: could not allocate memory for mtlc");
        [argValue addObject: [NSNumber numberWithLong: 0L]];
        return;
    }
    memset(mtlc, 0, sizeof(MTLContext));


    mtlc->mtlDevice = [CGDirectDisplayCopyCurrentMetalDevice(displayID) retain];

    mtlc->mtlVertexBuffer = [[mtlc->mtlDevice  newBufferWithBytes:verts
                                                           length:sizeof(verts)
                                                           options:MTLResourceCPUCacheModeDefaultCache] retain];

    NSError *error = nil;
    NSLog(@"Load shader library from %@", mtlShadersLib);

    mtlc->mtlLibrary = [mtlc->mtlDevice newLibraryWithFile: mtlShadersLib error:&error];
    if (!mtlc->mtlLibrary) {
        NSLog(@"Failed to load library. error %@", error);
        exit(0);
    }
    id <MTLFunction> vertColFunc = [mtlc->mtlLibrary newFunctionWithName:@"vert_col"];
    id <MTLFunction> vertTxtFunc = [mtlc->mtlLibrary newFunctionWithName:@"vert_txt"];
    id <MTLFunction> fragColFunc = [mtlc->mtlLibrary newFunctionWithName:@"frag_col"];
    id <MTLFunction> fragTxtFunc = [mtlc->mtlLibrary newFunctionWithName:@"frag_txt"];

    // Create depth state.
    MTLDepthStencilDescriptor *depthDesc = [MTLDepthStencilDescriptor new];
    depthDesc.depthCompareFunction = MTLCompareFunctionLess;
    depthDesc.depthWriteEnabled = YES;

    MTLVertexDescriptor *vertDesc = [MTLVertexDescriptor new];
    vertDesc.attributes[VertexAttributePosition].format = MTLVertexFormatFloat3;
    vertDesc.attributes[VertexAttributePosition].offset = 0;
    vertDesc.attributes[VertexAttributePosition].bufferIndex = MeshVertexBuffer;
    vertDesc.layouts[MeshVertexBuffer].stride = sizeof(struct Vertex);
    vertDesc.layouts[MeshVertexBuffer].stepRate = 1;
    vertDesc.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;

    // Create pipeline state.
    MTLRenderPipelineDescriptor *pipelineDesc = [MTLRenderPipelineDescriptor new];
    pipelineDesc.sampleCount = 1;
    pipelineDesc.vertexFunction = vertColFunc;
    pipelineDesc.fragmentFunction = fragColFunc;
    pipelineDesc.vertexDescriptor = vertDesc;
    pipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    mtlc->mtlPipelineState = [mtlc->mtlDevice newRenderPipelineStateWithDescriptor:pipelineDesc error:&error];
    if (!mtlc->mtlPipelineState) {
        NSLog(@"Failed to create pipeline state, error %@", error);
        exit(0);
    }

    vertDesc = [MTLVertexDescriptor new];
    vertDesc.attributes[VertexAttributePosition].format = MTLVertexFormatFloat3;
    vertDesc.attributes[VertexAttributePosition].offset = 0;
    vertDesc.attributes[VertexAttributePosition].bufferIndex = MeshVertexBuffer;
    vertDesc.attributes[VertexAttributeTexPos].format = MTLVertexFormatFloat2;
    vertDesc.attributes[VertexAttributeTexPos].offset = 3*sizeof(float);
    vertDesc.attributes[VertexAttributeTexPos].bufferIndex = MeshVertexBuffer;
    vertDesc.layouts[MeshVertexBuffer].stride = sizeof(struct TxtVertex);
    vertDesc.layouts[MeshVertexBuffer].stepRate = 1;
    vertDesc.layouts[MeshVertexBuffer].stepFunction = MTLVertexStepFunctionPerVertex;
    // Create pipeline state.
    pipelineDesc = [MTLRenderPipelineDescriptor new];
    pipelineDesc.sampleCount = 1;
    pipelineDesc.vertexFunction = vertTxtFunc;
    pipelineDesc.fragmentFunction = fragTxtFunc;
    pipelineDesc.vertexDescriptor = vertDesc;
    pipelineDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    mtlc->mtlBlitPipelineState = [mtlc->mtlDevice newRenderPipelineStateWithDescriptor:pipelineDesc error:&error];
    if (!mtlc->mtlBlitPipelineState) {
        NSLog(@"Failed to create pipeline state, error %@", error);
        exit(0);
    }

    mtlc->mtlCommandBuffer = nil;

    // Create command queue
    mtlc->mtlCommandQueue = [mtlc->mtlDevice newCommandQueue];
    mtlc->mtlEmptyCommandBuffer = YES;

    mtlc->caps = caps;

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

  //  [NSOpenGLContext clearCurrentContext];
    [argValue addObject: [NSNumber numberWithLong:ptr_to_jlong(mtlinfo)]];
    [pool drain];
}
@end //GraphicsConfigUtil

JNIEXPORT jint JNICALL
Java_sun_java2d_metal_MTLGraphicsConfig_getMTLCapabilities
    (JNIEnv *env, jclass mtlgc, jlong configInfo)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLGraphicsConfig_getMTLCapabilities");

    MTLGraphicsConfigInfo *mtlinfo =
        (MTLGraphicsConfigInfo *)jlong_to_ptr(configInfo);
    if ((mtlinfo == NULL) || (mtlinfo->context == NULL)) {
        return CAPS_EMPTY;
    } else {
        return mtlinfo->context->caps;
    }
}

JNIEXPORT jint JNICALL
Java_sun_java2d_metal_MTLGraphicsConfig_nativeGetMaxTextureSize
    (JNIEnv *env, jclass mtlgc)
{
    J2dTraceLn(J2D_TRACE_INFO, "MTLGraphicsConfig_nativeGetMaxTextureSize");

    __block int max = 0;

//    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
//    }];

    return (jint)max;
}
