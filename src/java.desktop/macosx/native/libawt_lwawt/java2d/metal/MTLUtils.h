#ifndef MTLUtils_h_Included
#define MTLUtils_h_Included

#import <Metal/Metal.h>

float MTLUtils_normalizeX(id<MTLTexture> dest, float x);
float MTLUtils_normalizeY(id<MTLTexture> dest, float y);

#endif /* MTLUtils_h_Included */
