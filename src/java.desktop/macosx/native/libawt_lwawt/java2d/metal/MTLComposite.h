#ifndef MTLComposite_h_Included
#define MTLComposite_h_Included

#import <Metal/Metal.h>

#include <jni.h>

@interface MTLComposite : NSObject
- (id)init;
- (BOOL)isEqual:(MTLComposite *)other;
- (void)copyFrom:(MTLComposite *)other;

- (void)setRule:(jint)rule; // sets extraAlpha=1
- (void)setRule:(jint)rule extraAlpha:(jfloat)extraAlpha;
- (void)reset;

- (jint)getRule;

- (jboolean)isBlendingDisabled:(jboolean) isSrcOpaque;
- (NSString *)getDescription; // creates autorelease string
@end

#endif // MTLComposite_h_Included