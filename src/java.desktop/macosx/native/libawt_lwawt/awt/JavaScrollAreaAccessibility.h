// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaElementAccessibility.h"

@interface JavaScrollAreaAccessibility : JavaElementAccessibility

@property(readonly) NSArray *accessibleContents;
@property(readonly) id accessibleVerticalScrollBar;
@property(readonly) id accessibleHorizontalScrollBar;

@end

@interface PlatformAxScrollArea : PlatformAxElement
@end
