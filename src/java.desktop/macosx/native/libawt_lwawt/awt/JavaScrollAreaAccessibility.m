// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaScrollAreaAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

@implementation JavaScrollAreaAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxScrollArea";
}

- (NSArray *)accessibleContents {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaBaseAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:YES];

    if ([children count] <= 0) return nil;
    NSArray *contents = [NSMutableArray arrayWithCapacity:[children count]];

    // The scroll bars are in the children. children less the scroll bars is the contents
    NSEnumerator *enumerator = [children objectEnumerator];
    PlatformAxElement *aElement;
    while ((aElement = [enumerator nextObject])) {
        if ([aElement respondsToSelector:@selector(accessibilityRole)]) {
            if (![[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
                // no scroll bars in contents
                [(NSMutableArray *) contents addObject:aElement];
            }
        }
    }

    return contents;
}

- (id)accessibleVerticalScrollBar {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    NSArray *children = [JavaBaseAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:YES];
    if ([children count] <= 0) return nil;

    // The scroll bars are in the children.
    NSEnumerator *enumerator = [children objectEnumerator];
    PlatformAxElement *aElement;
    while ((aElement = (PlatformAxElement *)[enumerator nextObject])) {
        if ([[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
            jobject elementAxContext = [[aElement javaBase] axContextWithEnv:env];
            if (isVertical(env, elementAxContext, fComponent)) {
                (*env)->DeleteLocalRef(env, elementAxContext);
                return aElement;
            }
            (*env)->DeleteLocalRef(env, elementAxContext);
        }
    }

    return nil;
}

- (id)accessibleHorizontalScrollBar {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    NSArray *children = [JavaBaseAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:YES];
    if ([children count] <= 0) return nil;

    // The scroll bars are in the children.
    PlatformAxElement *aElement;
    NSEnumerator *enumerator = [children objectEnumerator];
    while ((aElement = [enumerator nextObject])) {
        if ([aElement respondsToSelector:@selector(accessibilityRole)]) {
            if ([[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
                jobject elementAxContext = [[aElement javaBase] axContextWithEnv:env];
                if (isHorizontal(env, elementAxContext, fComponent)) {
                    (*env)->DeleteLocalRef(env, elementAxContext);
                    return aElement;
                }
                (*env)->DeleteLocalRef(env, elementAxContext);
            }
        }
    }

    return nil;
}

@end

@implementation PlatformAxScrollArea

- (NSArray *)accessibilityContents {
    return [(JavaScrollAreaAccessibility *)[self javaBase] accessibleContents];
}

- (id)accessibilityVerticalScrollBar {
    return [(JavaScrollAreaAccessibility *)[self javaBase] accessibleVerticalScrollBar];
}

- (id)accessibilityHorizontalScrollBar {
    return [(JavaScrollAreaAccessibility *)[self javaBase] accessibleHorizontalScrollBar];
}

@end
