// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaScrollAreaAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

@implementation JavaScrollAreaAccessibility

// NSAccessibilityElement protocol methods

- (NSArray *)accessibilityContents {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaComponentAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:NO];

    if ([children count] <= 0) return nil;
    NSMutableArray *contents = [NSMutableArray arrayWithCapacity:[children count]];

    // The scroll bars are in the children. children less the scroll bars is the contents
    for (id aElement in children) {
        if (![[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
            // no scroll bars in contents
            [(NSMutableArray *) contents addObject:aElement];
        }
    }

    return [NSArray arrayWithArray:contents];
}

- (id)accessibilityVerticalScrollBar {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    NSArray *children = [JavaComponentAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:NO];
    if ([children count] <= 0) return nil;

    // The scroll bars are in the children.
    NSEnumerator *enumerator = [children objectEnumerator];
    id aElement;
    while ((aElement = [enumerator nextObject])) {
        if ([[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
            jobject elementAxContext = [aElement axContextWithEnv:env];
            if (isVertical(env, elementAxContext, fComponent)) {
                (*env)->DeleteLocalRef(env, elementAxContext);
                return aElement;
            }
            (*env)->DeleteLocalRef(env, elementAxContext);
        }
    }

    return nil;
}

- (id)accessibilityHorizontalScrollBar {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    NSArray *children = [JavaComponentAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:NO];
    if ([children count] <= 0) return nil;

    // The scroll bars are in the children.
    id aElement;
    NSEnumerator *enumerator = [children objectEnumerator];
    while ((aElement = [enumerator nextObject])) {
        if ([[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
            jobject elementAxContext = [aElement axContextWithEnv:env];
            if (isHorizontal(env, elementAxContext, fComponent)) {
                (*env)->DeleteLocalRef(env, elementAxContext);
                return aElement;
            }
            (*env)->DeleteLocalRef(env, elementAxContext);
        }
    }

    return nil;
}

@end
