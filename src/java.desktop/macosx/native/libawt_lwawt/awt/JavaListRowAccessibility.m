// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaListRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaListAccessibility.h"
#import "ThreadUtilities.h"

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");

@implementation JavaListRowAccessibility

// NSAccessibilityElement protocol methods

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityRowRole;;
}

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        JavaComponentAccessibility *newChild = [JavaComponentAccessibility createWithParent:self
                                                                       accessible:self->fAccessible
                                                                             role:self->fJavaRole
                                                                            index:self->fIndex
                                                                          withEnv:[ThreadUtilities getJNIEnv]
                                                                         withView:self->fView
                                                                        isWrapped:YES];
        return [NSArray arrayWithObject:[newChild autorelease]];
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
