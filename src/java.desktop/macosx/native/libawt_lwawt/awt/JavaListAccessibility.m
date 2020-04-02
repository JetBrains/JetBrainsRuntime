// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaListAccessibility.h"
#import "ThreadUtilities.h"

@implementation JavaListAccessibility

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilityRows
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaElementAccessibility childrenOfParent:self
                                                           withEnv:env
                                                  withChildrenCode:JAVA_AX_ALL_CHILDREN
                                                      allowIgnored:NO];
    NSArray *value = nil;
    if ([children count] > 0) {
        value = children;
    }
    return value;
}

- (BOOL)isAccessibilityElement
{
    return YES;
}

- (NSString *)accessibilityLabel
{
    return @"List";
}

- (NSArray *)accessibilitySelectedChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *selectedChildren = [JavaElementAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_SELECTED_CHILDREN allowIgnored:NO];
    if ([selectedChildren count] > 0) {
        return selectedChildren;
    }

    return nil;
}

- (NSRect)accessibilityFrame
{
    return [super accessibilityFrame]; // todo: implement
}

@end

//@implementation JavaRowAccessibility
//@end
