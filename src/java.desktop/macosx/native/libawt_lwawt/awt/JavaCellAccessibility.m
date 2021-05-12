// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaCellAccessibility.h"
#import "ThreadUtilities.h"

@implementation JavaCellAccessibility

// NSAccessibilityElement protocol methods

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityCellRole;;
}

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        NSString *javaRole = [self  javaRole];
        JavaComponentAccessibility *newChild = [JavaComponentAccessibility createWithParent:self
                                                                       accessible:self->fAccessible
                                                                             role:javaRole
                                                                            index:self->fIndex
                                                                          withEnv:[ThreadUtilities getJNIEnv]
                                                                         withView:self->fView
                                                                        isWrapped:YES];
        return [NSArray arrayWithObject:newChild];
    } else {
        return children;
    }
}

- (NSRect)accessibilityFrame {
    return [super accessibilityFrame];
}

- (id)accessibilityParent {
    return [super accessibilityParent];
}

- (void)setAccessibilityParent:(id)accessibilityParent {
    [super setAccessibilityParent:accessibilityParent];
}

@end
