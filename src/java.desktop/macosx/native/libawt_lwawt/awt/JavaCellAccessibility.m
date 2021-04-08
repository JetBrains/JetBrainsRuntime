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
        NSString *javaRole = [[self javaBase] javaRole];
        JavaElementAccessibility *newChild = [JavaElementAccessibility createWithParent:[self javaBase]
                                                                       accessible:[[self javaBase] accessible]
                                                                             role:javaRole
                                                                            index:[[self javaBase] index]
                                                                          withEnv:[ThreadUtilities getJNIEnv]
                                                                         withView:[[self javaBase] view]
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
