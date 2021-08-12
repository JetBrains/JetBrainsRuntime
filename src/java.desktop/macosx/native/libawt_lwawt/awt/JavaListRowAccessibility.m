// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaListRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaListAccessibility.h"
#import "ThreadUtilities.h"

@implementation JavaListRowAccessibility

// NSAccessibilityElement protocol methods

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityRowRole;;
}

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {

        // Since the row element has already been created, we should no create it again, but just retrieve it by a pointer, that's why isWrapped is set to YES.
        JavaComponentAccessibility *newChild = [JavaComponentAccessibility createWithParent:self
                                                                                 accessible:self->fAccessible
                                                                                       role:self->fJavaRole
                                                                                      index:self->fIndex
                                                                                    withEnv:[ThreadUtilities getJNIEnv]
                                                                                   withView:self->fView
                                                                                  isWrapped:YES];
        return [NSArray arrayWithObject:newChild];
    } else {
        return children;
    }
}

- (NSInteger)accessibilityIndex {
    return [[self accessibilityParent] accessibilityIndexOfChild:self];
}

- (id)accessibilityParent
{
    return [super accessibilityParent];
}

- (NSRect)accessibilityFrame {
    return [super accessibilityFrame];
}

@end
