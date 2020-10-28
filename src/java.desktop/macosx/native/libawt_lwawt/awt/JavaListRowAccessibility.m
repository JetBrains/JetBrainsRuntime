// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaListRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTextAccessibility.h"
#import "JavaListAccessibility.h"
#import "ThreadUtilities.h"

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");

@implementation JavaListRowAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxListRow";
}

@end

@implementation PlatformAxListRow

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        NSString *javaRole = [[self javaBase] javaRole];
        JavaBaseAccessibility *newChild = nil;
        if ([javaRole isEqualToString:@"pagetablist"]) {
            newChild = [TabGroupAccessibility alloc];
        } else if ([javaRole isEqualToString:@"scrollpane"]) {
            newChild = [ScrollAreaAccessibility alloc];
        } else {
            NSString *nsRole = [sRoles objectForKey:javaRole];
            if ([nsRole isEqualToString:NSAccessibilityStaticTextRole] || [nsRole isEqualToString:NSAccessibilityTextAreaRole] || [nsRole isEqualToString:NSAccessibilityTextFieldRole]) {
                newChild = [JavaTextAccessibility alloc];
            } else if ([nsRole isEqualToString:NSAccessibilityListRole]) {
                newChild = [JavaListAccessibility alloc];
            } else {
                newChild = [JavaComponentAccessibility alloc];
            }
        }
        [newChild initWithParent:[self javaBase]
                         withEnv:[ThreadUtilities getJNIEnv]
                  withAccessible:[[self javaBase] accessible]
                       withIndex:[[self javaBase] index]
                        withView:[[self javaBase] view]
                    withJavaRole:javaRole];
        return [NSArray arrayWithObject:[newChild autorelease].platformAxElement];
    } else {
        return children;
    }
}

- (NSInteger)accessibilityIndex {
    if ([self isTableRow]) {
        return [[self javaBase] index];
    }
    return [super accessibilityIndex];
}

- (id)accessibilityParent
{
    return [super accessibilityParent];
}

@end
