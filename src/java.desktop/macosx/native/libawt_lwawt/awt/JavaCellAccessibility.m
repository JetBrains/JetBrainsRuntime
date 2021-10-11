// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaCellAccessibility.h"
#import "ThreadUtilities.h"

@implementation JavaCellAccessibility

// NSAccessibilityElement protocol methods

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityCellRole;;
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
