// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaBaseAccessibility.h"

@interface JavaElementAccessibility : JavaBaseAccessibility
@end

@interface PlatformAxElement : NSAccessibilityElement <JavaBaseProvider>

// begin of NSAccessibility protocol methods
- (BOOL)isAccessibilityElement;
- (NSString *)accessibilityLabel;
- (NSArray *)accessibilityChildren;
- (NSArray *)accessibilitySelectedChildren;
- (NSRect)accessibilityFrame;
- (id)accessibilityParent;
- (BOOL)accessibilityIsIgnored;
- (BOOL)isAccessibilityEnabled;
- (id)accessibilityApplicationFocusedUIElement;
- (id)getAccessibilityWindow;
// end of NSAccessibility protocol methods

@end
