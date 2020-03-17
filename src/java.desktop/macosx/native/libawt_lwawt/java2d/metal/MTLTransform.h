#ifndef MTLTransform_h_Included
#define MTLTransform_h_Included

#import <Metal/Metal.h>

#include <jni.h>

@interface MTLTransform : NSObject
- (id)init;
- (BOOL)isEqual:(MTLTransform *)other;
- (void)copyFrom:(MTLTransform *)other;

- (void)setTransformM00:(jdouble) m00 M10:(jdouble) m10
                    M01:(jdouble) m01 M11:(jdouble) m11
                    M02:(jdouble) m02 M12:(jdouble) m12;
- (void)resetTransform;

- (void)setVertexMatrix:(id<MTLRenderCommandEncoder>)encoder
              destWidth:(NSUInteger)dw
             destHeight:(NSUInteger)dh;
@end

#endif // MTLTransform_h_Included
