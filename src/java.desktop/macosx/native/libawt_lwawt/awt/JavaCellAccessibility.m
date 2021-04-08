// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaCellAccessibility.h"
#import "ThreadUtilities.h"

@implementation JavaCellAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxCell";
}

@end

@implementation PlatformAxCell

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityCellRole;;
}

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        NSString *javaRole = [[self javaComponent] javaRole];
        JavaComponentAccessibility *newChild = [JavaComponentAccessibility createWithParent:[self javaComponent]
                                                                       accessible:[[self javaComponent] accessible]
                                                                             role:javaRole
                                                                            index:[[self javaComponent] index]
                                                                          withEnv:[ThreadUtilities getJNIEnv]
                                                                         withView:[[self javaComponent] view]
                                                                        isWrapped:YES];
        return [NSArray arrayWithObject:newChild.platformAxElement];
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
