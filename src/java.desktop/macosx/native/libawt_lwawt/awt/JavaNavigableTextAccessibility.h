// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaStaticTextAccessibility.h"

@interface JavaNavigableTextAccessibility : JavaStaticTextAccessibility

// protocol methods
- (NSValue *)accessibleBoundsForRange:(NSRange)range;
- (NSNumber *)accessibleLineForIndex:(NSInteger)index;
- (NSValue *)accessibleRangeForLine:(NSInteger)line;
- (NSString *)accessibleStringForRange:(NSRange)range;

- (NSValue *)accessibleRangeForIndex:(NSInteger)index;
- (NSValue *)accessibleRangeForPosition:(NSPoint)point;

@property(readonly) NSString *accessibleSelectedText;
@property(readonly) NSValue *accessibleSelectedTextRange;
@property(readonly) NSNumber *accessibleNumberOfCharacters;
@property(readonly) NSNumber *accessibleInsertionPointLineNumber;
@property(readonly) BOOL accessibleIsValueSettable;
@property(readonly) BOOL accessibleIsPasswordText;

- (void)accessibleSetSelectedText:(NSString *)accessibilitySelectedText;
- (void)accessibleSetSelectedTextRange:(NSRange)accessibilitySelectedTextRange;

@end

@interface PlatformAxNavigableText : PlatformAxStaticText <NSAccessibilityNavigableStaticText>
@end
