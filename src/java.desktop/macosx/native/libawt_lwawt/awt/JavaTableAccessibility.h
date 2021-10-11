// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComponentAccessibility.h"

@interface JavaTableAccessibility : JavaComponentAccessibility <NSAccessibilityTable>
{
    // A table row object does not have java peer, but the platform a11y requires that a table has rows.
    // So it is hard linked in the cache which follows the table modifications and life cycle.
    NSMutableDictionary<NSNumber*, id> *rowCache;
}

@property(readonly) int accessibleRowCount;
@property(readonly) int accessibleColCount;
@property(readonly) NSArray<NSNumber *> *selectedAccessibleRows;
@property(readonly) NSArray<NSNumber *> *selectedAccessibleColumns;
- (BOOL)isAccessibleChildSelectedFromIndex:(int)index;
- (int) accessibleRowAtIndex:(int)index;
- (int) accessibleColumnAtIndex:(int)index;
- (JavaTableAccessibility *)createRowForIndex:(NSNumber *)index;
- (void)clearCache;

@end
