// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaElementAccessibility.h"

@interface JavaStaticTextAccessibility : JavaElementAccessibility

/*
 * Converts an int array to an NSRange wrapped inside an NSValue
 * takes [start, end] values and returns [start, end - start]
 */
NSValue *javaConvertIntArrayToNSRangeValue(JNIEnv* env, jintArray array);

@property(readonly) NSString *accessibleValue;
@property(readonly) NSValue *accessibleVisibleCharacterRange;

@end

@interface PlatformAxStaticText : PlatformAxElement <NSAccessibilityStaticText>
@end
