// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaElementAccessibility.h"

@interface JavaTabButtonAccessibility : JavaElementAccessibility {
    jobject fTabGroupAxContext;
}

// from TabGroup controller
- (id)initWithParent:(NSObject *)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withTabGroup:(jobject)tabGroup withView:(NSView *)view withJavaRole:(NSString *)javaRole;

@property(readonly) jobject tabGroup;
@property(readonly) id accessibleValue;
- (void)performPressAction;

@end

@interface PlatformAxTabButton : PlatformAxElement
@end
