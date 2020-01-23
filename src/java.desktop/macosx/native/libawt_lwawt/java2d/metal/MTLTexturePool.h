#ifndef MTLTexturePool_h_Included
#define MTLTexturePool_h_Included
#import "MTLUtils.h"

@interface MTLTexturePoolItem : NSObject
@property (readwrite, retain) id<MTLTexture> texture;
@property (readwrite) bool isBusy;
@property (readwrite, retain) NSDate * lastUsed;
@property (readwrite) bool isMultiSample;

- (id) initWithTexture:(id<MTLTexture>)tex;
@end

@interface MTLPooledTextureHandle : NSObject
@property (readonly, assign) id<MTLTexture> texture;
@property (readonly) MTLRegion rect;
- (void) releaseTexture;
@end

// NOTE: owns all MTLTexture objects
@interface MTLTexturePool : NSObject
@property (readwrite, retain) id<MTLDevice> device;

- (id) initWithDevice:(id<MTLDevice>)device;
- (MTLPooledTextureHandle *) getTexture:(int)width height:(int)height format:(MTLPixelFormat)format;
- (MTLPooledTextureHandle *) getTexture:(int)width height:(int)height format:(MTLPixelFormat)format
                          isMultiSample:(bool)isMultiSample;
@end

#endif /* MTLTexturePool_h_Included */
