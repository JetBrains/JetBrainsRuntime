// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComponentAccessibility.h"

/*
 * Converts an int array to an NSRange wrapped inside an NSValue
 * takes [start, end] values and returns [start, end - start]
 */
NSValue *javaConvertIntArrayToNSRangeValue(JNIEnv* env, jintArray array);

@interface JavaStaticTextAccessibility : JavaComponentAccessibility <NSAccessibilityStaticText>
@end
