// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTextAccessibility.h"
#import "JavaListAccessibility.h"
#import "ThreadUtilities.h"

@implementation JavaRowAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxRow";
}

@end

@implementation PlatformAxRow

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
        return [NSArray arrayWithObject:[newChild autorelease]];
    } else {
        return children;
    }
}

- (NSInteger)accessibilityIndex {
    return [[self accessibilityParent] accessibilityIndexOfChild:self];
}

- (NSString *)accessibilityLabel {
    return [super accessibilityLabel];
}

// to avoid warning (why?): method in protocol 'NSAccessibilityElement' not implemented
- (NSRect)accessibilityFrame
{
    return [super accessibilityFrame];
}

// to avoid warning (why?): method in protocol 'NSAccessibilityElement' not implemented
- (id)accessibilityParent
{
    return [super accessibilityParent];
}

@end
