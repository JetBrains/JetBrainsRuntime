#ifndef MTLComposite_h_Included
#define MTLComposite_h_Included

#import <Metal/Metal.h>

#include <jni.h>

#define FLT_EPS (0.001f)
#define FLT_LT(x,y) ((x) < (y) - FLT_EPS)
#define FLT_GE(x,y) ((x) >= (y) - FLT_EPS)
#define FLT_LE(x,y) ((x) <= (y) + FLT_EPS)
#define FLT_GT(x,y) ((x) > (y) + FLT_EPS)

/**
 * The MTLComposite class represents composite mode
 * */

@interface MTLComposite : NSObject
- (id)init;
- (BOOL)isEqual:(MTLComposite *)other; // used to compare requested with cached
- (void)copyFrom:(MTLComposite *)other; // used to save cached

- (void)setRule:(jint)rule; // sets extraAlpha=1
- (void)setRule:(jint)rule extraAlpha:(jfloat)extraAlpha;
- (void)reset;

- (void)setXORComposite:(jint)color;
- (void)setAlphaComposite:(jint)rule;


- (jint)getCompositeState;
- (jint)getRule;
- (jint)getXorColor;
- (jfloat)getExtraAlpha;

- (NSString *)getDescription; // creates autorelease string
@end

#endif // MTLComposite_h_Included
