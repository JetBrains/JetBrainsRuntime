/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
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

#import "MetalGraphicsConfig.h"
#import "MetalLayer.h"
#import "shaders/MetalShaderTypes.h"
#import "ThreadUtilities.h"
#import "LWCToolkit.h"
#import "MetalSurfaceData.h"
#import "VertexDataManager.h"


//extern NSOpenGLPixelFormat *sharedPixelFormat;
//extern NSOpenGLContext *sharedContext;

@implementation MetalLayer

@synthesize javaLayer;
@synthesize mtlTexture;
//@synthesize target;
@synthesize textureWidth;
@synthesize textureHeight;
@synthesize mtlLibrary;
@synthesize mtlRenderPipelineDescriptor;
@synthesize renderPipelineState;
//@synthesize LineVertexBuffer;
//@synthesize QuadVertexBuffer;
#ifdef REMOTELAYER
//@synthesize parentLayer;
//@synthesize remoteLayer;
//@synthesize jrsRemoteLayer;
#endif

- (id) initWithJavaLayer:(JNFWeakJObjectWrapper *)layer;
{
AWT_ASSERT_APPKIT_THREAD;
    // Initialize ourselves
    self = [super init];
    if (self == nil) return self;

    self.javaLayer = layer;

    // NOTE: async=YES means that the layer is re-cached periodically
    //self.displaySyncEnabled = NO;
    self.contentsGravity = kCAGravityTopLeft;
    //Layer backed view
    //self.needsDisplayOnBoundsChange = YES;
    //self.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;

    //fprintf(stdout, "MetalLayer_initWithJavaLayer\n");fflush(stdout);
    //Disable CALayer's default animation
    NSMutableDictionary * actions = [[NSMutableDictionary alloc] initWithObjectsAndKeys:
                                    [NSNull null], @"anchorPoint",
                                    [NSNull null], @"bounds",
                                    [NSNull null], @"contents",
                                    [NSNull null], @"contentsScale",
                                    [NSNull null], @"onOrderIn",
                                    [NSNull null], @"onOrderOut",
                                    [NSNull null], @"position",
                                    [NSNull null], @"sublayers",
                                    nil];
    self.actions = actions;
    [actions release];

    //textureID = 0; // texture will be created by rendering pipe
    mtlTexture = NULL;
    //target = 0;

    return self;
}

- (void) dealloc {
    self.javaLayer = nil;
    [super dealloc];

    VertexDataManager_freeAllPrimitives();
}

/*
- (CGLPixelFormatObj)copyCGLPixelFormatForDisplayMask:(uint32_t)mask {
    return CGLRetainPixelFormat(sharedPixelFormat.CGLPixelFormatObj);
}

- (CGLContextObj)copyCGLContextForPixelFormat:(CGLPixelFormatObj)pixelFormat {
    CGLContextObj contextObj = NULL;
    CGLCreateContext(pixelFormat, sharedContext.CGLContextObj, &contextObj);
    return contextObj;
}*/

// use texture (intermediate buffer) as src and blit it to the layer
/*- (void) blitTexture
{
    if (textureID == 0) {
        return;
    }

    glEnable(target);
    glBindTexture(target, textureID);

    glTexEnvf(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE); // srccopy

    float swid = 1.0f, shgt = 1.0f;
    if (target == GL_TEXTURE_RECTANGLE_ARB) {
        swid = textureWidth;
        shgt = textureHeight;
    }
    glBegin(GL_QUADS);
    glTexCoord2f(0.0f, 0.0f); glVertex2f(-1.0f, -1.0f);
    glTexCoord2f(swid, 0.0f); glVertex2f( 1.0f, -1.0f);
    glTexCoord2f(swid, shgt); glVertex2f( 1.0f,  1.0f);
    glTexCoord2f(0.0f, shgt); glVertex2f(-1.0f,  1.0f);
    glEnd();

    glBindTexture(target, 0);
    glDisable(target);
}*/

/*-(BOOL)canDrawInCGLContext:(CGLContextObj)glContext pixelFormat:(CGLPixelFormatObj)pixelFormat forLayerTime:(CFTimeInterval)timeInterval displayTime:(const CVTimeStamp *)timeStamp{
    return textureID == 0 ? NO : YES;
}

-(void)drawInCGLContext:(CGLContextObj)glContext pixelFormat:(CGLPixelFormatObj)pixelFormat forLayerTime:(CFTimeInterval)timeInterval displayTime:(const CVTimeStamp *)timeStamp
{
    AWT_ASSERT_APPKIT_THREAD;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_CLASS_CACHE(jc_JavaLayer, "sun/java2d/opengl/CGLLayer");
    static JNF_MEMBER_CACHE(jm_drawInCGLContext, jc_JavaLayer, "drawInCGLContext", "()V");

    jobject javaLayerLocalRef = [self.javaLayer jObjectWithEnv:env];
    if ((*env)->IsSameObject(env, javaLayerLocalRef, NULL)) {
        return;
    }

    // Set the current context to the one given to us.
    CGLSetCurrentContext(glContext);

    // Should clear the whole CALayer, because it can be larger than our texture.
    glClearColor(0.0, 0.0, 0.0, 0.0);
    glClear(GL_COLOR_BUFFER_BIT);

    glViewport(0, 0, textureWidth, textureHeight);

    JNFCallVoidMethod(env, javaLayerLocalRef, jm_drawInCGLContext);
    (*env)->DeleteLocalRef(env, javaLayerLocalRef);

    // Call super to finalize the drawing. By default all it does is call glFlush().
    [super drawInCGLContext:glContext pixelFormat:pixelFormat forLayerTime:timeInterval displayTime:timeStamp];

    CGLSetCurrentContext(NULL);
}*/

@end



static jlong cachedLayer = 0;
static float drawColor[4] = {0.0, 0.0, 0.0, 0.0};
/*
 * Class:     sun_java2d_metal_MetalLayer
 * Method:    nativeCreateLayer
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL
Java_sun_java2d_metal_MetalLayer_nativeCreateLayer
(JNIEnv *env, jobject obj)
{
    __block MetalLayer *layer = nil;

    //fprintf(stdout, "MetalLayer_nativeCreateLayer\n");fflush(stdout);
JNF_COCOA_ENTER(env);

    JNFWeakJObjectWrapper *javaLayer = [JNFWeakJObjectWrapper wrapperWithJObject:obj withEnv:env];

    [ThreadUtilities performOnMainThreadWaiting:YES block:^(){
            AWT_ASSERT_APPKIT_THREAD;
        
            layer = [[MetalLayer alloc] initWithJavaLayer: javaLayer];

            //cachedLayer = ptr_to_jlong(layer);
    }];
    
JNF_COCOA_EXIT(env);

    return ptr_to_jlong(layer);
}



JNIEXPORT jlong JNICALL
Java_sun_java2d_metal_MetalLayer_nativeInitLayer
(JNIEnv *env, jobject obj, jlong configInfo, jlong layer)
{

JNF_COCOA_ENTER(env);
    MetalGraphicsConfigInfo *pInfo =
        (MetalGraphicsConfigInfo *)jlong_to_ptr(configInfo);
    if ((pInfo == NULL)) {
        return -1;
    }

    MetalLayer *mtlLayer = jlong_to_ptr(layer);

    mtlLayer.device = pInfo->device;
    mtlLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;

    //mtlLayer.commandQueue = pInfo->commandQueue;

    // ------------------------------------------------------------------------------------------------
    // TODO : Currently we manually compile and copy the shader library to /tmp.
    //        Need to complete build changes - to build it and read from some other location within jdk
    // ------------------------------------------------------------------------------------------------
    // Load shader library
    /*NSError *error = nil;
    mtlLayer.mtlLibrary = [mtlLayer.device newLibraryWithFile: @"/tmp/BaseShader.metallib" error:&error];
    if (!mtlLayer.mtlLibrary) {
        NSLog(@"Failed to load library. error %@", error);
        //exit(0);
    }*/

    NSError* error = nil;
    NSString* content = @"#include <metal_stdlib>\n"
    "#include <simd/simd.h>\n"
    "using namespace metal;\n"
    "struct MetalVertex"
    "{"
    "vector_float4 position;"
    "vector_float4 color;"
    "};\n"
    "struct VertexOut {"
    "float4 color;"
    "float4 pos [[position]];"
    "};\n"
    "vertex VertexOut vertexShader(device MetalVertex *vertices [[buffer(0)]], uint vid [[vertex_id]]) {"
    "VertexOut out;"
    "\n"
    "out.pos = vertices[vid].position;"
    "out.color = vertices[vid].color;"
    "return out;"
    "}\n"
    "fragment float4 fragmentShader(MetalVertex in [[stage_in]]) {"
    "return in.color;"
    "}";

    mtlLayer.mtlLibrary = [mtlLayer.device newLibraryWithSource:content options:nil error:&error];
    if (!mtlLayer.mtlLibrary) {
        NSLog(@"Failed to create shader library from source. error %@", error);
        //exit(0);
    }

    //create a vertex and fragment objects
    id<MTLFunction> vertexProgram = [mtlLayer.mtlLibrary newFunctionWithName:@"vertexShader"];
    id<MTLFunction> fragmentProgram = [mtlLayer.mtlLibrary newFunctionWithName:@"fragmentShader"];

    
    mtlLayer.mtlRenderPipelineDescriptor = [[MTLRenderPipelineDescriptor alloc] init];
    
    [mtlLayer.mtlRenderPipelineDescriptor setVertexFunction:vertexProgram];
    [mtlLayer.mtlRenderPipelineDescriptor setFragmentFunction:fragmentProgram];
    
    //specify the target-texture pixel format
    mtlLayer.mtlRenderPipelineDescriptor.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    
    //create the Rendering Pipeline Object
    mtlLayer.renderPipelineState = [mtlLayer.device newRenderPipelineStateWithDescriptor:mtlLayer.mtlRenderPipelineDescriptor error:nil];

    VertexDataManager_init(mtlLayer.device);
  

JNF_COCOA_EXIT(env);

    return ptr_to_jlong(layer);
}



// Must be called under the RQ lock.
/*JNIEXPORT void JNICALL
Java_sun_java2d_metal_MetalLayer_nativeValidate
(JNIEnv *env, jclass cls, jlong layer, jlong view)
{

JNF_COCOA_ENTER(env);

    MetalLayer *mtlLayer = jlong_to_ptr(layer);
    NSView *nsView = jlong_to_ptr(view);

    mtlLayer.frame = nsView.bounds;
    [mtlLayer setDrawableSize: nsView.bounds.size];

    mtlLayer.textureWidth = nsView.bounds.size.width;
    mtlLayer.textureHeight = nsView.bounds.size.height;

    NSLog(@"Validate : Width : %f", nsView.bounds.size.width);
    NSLog(@"Validate : Height : %f", nsView.bounds.size.height);

JNF_COCOA_EXIT(env);
}*/

// Must be called under the RQ lock.
JNIEXPORT void JNICALL
Java_sun_java2d_metal_MetalLayer_validate
(JNIEnv *env, jclass cls, jlong layerPtr, jobject surfaceData)
{
    MetalLayer *layer = OBJC(layerPtr);
    //fprintf(stdout, "MetalLayer_validate\n");fflush(stdout);
    if (surfaceData != NULL) {
        MetalSDOps *metalsdo = (MetalSDOps*) SurfaceData_GetOps(env, surfaceData);
        // TODO : Check whether we have to use pointer or instance variable
        //fprintf(stdout, "MetalLayer_validate replace mtlTexture\n");fflush(stdout);
        layer.mtlTexture = metalsdo->mtlTexture;
        //layer.target = GL_TEXTURE_2D;
        layer.textureWidth = metalsdo->width;
        layer.textureHeight = metalsdo->height;

        NSLog(@"Validate : Width : %f", layer.textureWidth);
        NSLog(@"Validate : height : %f", layer.textureHeight);

    } else {
        //fprintf(stdout, "MetalLayer_validate Null SD \n");fflush(stdout);
        //layer.textureID = 0;
    }
}



/*
// Must be called on the AppKit thread and under the RQ lock.
JNIEXPORT void JNICALL
Java_sun_java2d_opengl_CGLLayer_blitTexture
(JNIEnv *env, jclass cls, jlong layerPtr)
{
    CGLLayer *layer = jlong_to_ptr(layerPtr);

    [layer blitTexture];
}*/

JNIEXPORT void JNICALL
Java_sun_java2d_metal_MetalLayer_nativeSetScale
(JNIEnv *env, jclass cls, jlong layerPtr, jdouble scale)
{
    JNF_COCOA_ENTER(env);
    MetalLayer *layer = jlong_to_ptr(layerPtr);
    // We always call all setXX methods asynchronously, exception is only in 
    // this method where we need to change native texture size and layer's scale
    // in one call on appkit, otherwise we'll get window's contents blinking, 
    // during screen-2-screen moving.
    [ThreadUtilities performOnMainThreadWaiting:[NSThread isMainThread] block:^(){
        layer.contentsScale = scale;
    }];
    JNF_COCOA_EXIT(env);
}
