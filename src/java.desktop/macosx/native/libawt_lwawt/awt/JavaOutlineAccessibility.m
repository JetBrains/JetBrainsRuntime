// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaOutlineAccessibility.h"
#import "JavaAccessibilityUtilities.h"

@implementation JavaOutlineAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxOutline";
}

@end

@implementation PlatformAxOutline

- (NSString *)accessibilityLabel
{
    return [[super accessibilityLabel] isEqualToString:@"list"] ? @"tree" : [super accessibilityLabel];
}

@end
