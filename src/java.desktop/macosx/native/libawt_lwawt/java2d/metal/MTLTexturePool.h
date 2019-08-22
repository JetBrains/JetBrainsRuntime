#ifndef MTLTexturePool_h_Included
#define MTLTexturePool_h_Included
#import <Metal/Metal.h>

@interface MTLTexturePoolItem : NSObject
{
@private

id<MTLTexture> texture;
bool isBusy;
}

@property (readwrite, retain) id<MTLTexture> texture;
@property (readwrite, assign) bool isBusy;

- (id) initWithTexture:(id<MTLTexture>)tex;
@end

// NOTE: owns all MTLTexture objects
@interface MTLTexturePool : NSObject
{
@private

id<MTLDevice> device;
NSMutableArray<MTLTexturePoolItem*> * pool;
}

@property (readwrite, assign) id<MTLDevice> device;
@property (readwrite, retain) NSMutableArray<MTLTexturePoolItem*> * pool;

- (id) initWithDevice:(id<MTLDevice>)device;
- (id<MTLTexture>) getTexture:(int)width height:(int)height format:(MTLPixelFormat)format;
- (void) markTextureFree:(id<MTLTexture>)texture;
- (void) markAllTexturesFree;
@end

#endif /* MTLTexturePool_h_Included */
