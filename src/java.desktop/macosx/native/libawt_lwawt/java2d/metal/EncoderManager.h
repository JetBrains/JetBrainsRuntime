#ifndef EncoderManager_h_Included
#define EncoderManager_h_Included

#import <Metal/Metal.h>

#include <jni.h>
#include "MTLSurfaceDataBase.h"

/**
 * The EncoderManager class
 * */
@interface EncoderManager : NSObject
- (id)init;
- (void)dealloc;

- (void)setContext:(void *)mtlc;

- (id<MTLRenderCommandEncoder>)getRenderEncoder:(const BMTLSDOps *)dstOps;

- (id<MTLRenderCommandEncoder>)getTextureEncoder:(const BMTLSDOps *)dstOps
                                      isSrcOpaque:(bool)isSrcOpaque;

- (id<MTLRenderCommandEncoder>)getTextureEncoder:(id<MTLTexture>)dest
                                      isSrcOpaque:(bool)isSrcOpaque
                                      isDstOpaque:(bool)isDstOpaque;

- (id<MTLRenderCommandEncoder>)getEncoder:(const BMTLSDOps *)dstOps
                                 isTexture:(jboolean)isTexture
                                  srcFlags:(const SurfaceRasterFlags * )srcFlags;

// Base method to obtain MTLRenderCommandEncoder.
// It creates (or uses cached) encoder with states given from associated MTLContext
- (id<MTLRenderCommandEncoder>)getEncoder:(id<MTLTexture>)dest
                                  isOpaque:(jboolean)isOpaque
                                 isTexture:(jboolean)isTexture
                                  srcFlags:(const SurfaceRasterFlags * )srcFlags;

- (id<MTLBlitCommandEncoder>)createBlitEncoder;

- (void)endEncoder;
@end

#endif // EncoderManager_h_Included