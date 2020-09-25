// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import "sun_lwawt_macosx_CAccessibility.h"
#import "JavaRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTextAccessibility.h"
#import "JavaTableAccessibility.h"
#import "JavaCellAccessibility.h"
#import "ThreadUtilities.h"

static JNF_CLASS_CACHE(sjc_CAccessible, "sun/lwawt/macosx/CAccessible");
static JNF_MEMBER_CACHE(jf_ptr, sjc_CAccessible, "ptr", "J");
static JNF_STATIC_MEMBER_CACHE(jm_getAccessibleContext, sjc_CAccessible, "getActiveDescendant", "(Ljavax/accessibility/Accessible;)Ljavax/accessibility/AccessibleContext;");

@implementation JavaTableAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxTable";
}

@end

@implementation PlatformAxTable

- (NSArray *)accessibilityChildren {
        NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        return NULL;
    }
     int rowCount = 0, y = 0;
     for (id cell in children) {
     if (y != [cell accessibilityFrame].origin.y) {
     rowCount += 1;
     y = [cell accessibilityFrame].origin.y;
     }
     }
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jclass class = (*env)->GetObjectClass(env, [[self javaBase] accessible]);
    if (class == NULL) {
        printf("Класс не найден\n");
    } else {
        printf("Класс найден\n");
        NSString *a11yClassname = JNFObjectClassName(env, [[self javaBase] accessible]);
        printf("JNF класс %s\n", [a11yClassname UTF8String]);
        jobject ac = JNFCallStaticObjectMethod(env, [[self javaBase] accessible], jm_getAccessibleContext, [[self javaBase] accessible]);
        NSString *a11yContexClassname = JNFObjectClassName(env, ac);
        printf("JNF AccessibleContext класс %s\n", [a11yContexClassname UTF8String]);
    }
    NSMutableArray *rows = [NSMutableArray arrayWithCapacity:rowCount];
    int k = 0, cellCount = [children count] / rowCount;
    for (int i = 0; i < rowCount; i++) {
        NSMutableArray *cells = [NSMutableArray arrayWithCapacity:cellCount];
        NSMutableString *a11yName = @"row";
        CGFloat width = 0;
        for (int j = 0; j < cellCount; j++) {
            [cells addObject:[children objectAtIndex:k]];
            width += [[children objectAtIndex:k] accessibilityFrame].size.width;
            k += 1;
        }
        CGPoint point = [[cells objectAtIndex:0] accessibilityFrame].origin;
        CGFloat height = [[cells objectAtIndex:0] accessibilityFrame].size.height;
        NSAccessibilityElement *a11yRow = [NSAccessibilityElement accessibilityElementWithRole:NSAccessibilityRowRole frame:NSRectFromCGRect(CGRectMake(point.x, point.y, width, height)) label:a11yName parent:self];
        [a11yRow setAccessibilityChildren:cells];
        for (JavaCellAccessibility *cell in cells) [cell setAccessibilityParent:a11yRow];
        [rows addObject:a11yRow];
    }
    return rows;
}

- (NSArray *)accessibilityRows {
    return [self accessibilityChildren];
}
/*
- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilitySelectedRows {
    return [self accessibilitySelectedChildren];
}
 */

- (NSString *)accessibilityLabel {
    if (([super accessibilityLabel] == NULL) || [[super accessibilityLabel] isEqualToString:@""]) {
        return @"Table";
    }
    return [super accessibilityLabel];
}

/*
- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilityVisibleRows;
- (nullable NSArray *)accessibilityColumns;
- (nullable NSArray *)accessibilityVisibleColumns;
- (nullable NSArray *)accessibilitySelectedColumns;

- (nullable NSArray *)accessibilitySelectedCells;
- (nullable NSArray *)accessibilityVisibleCells;
- (nullable NSArray *)accessibilityRowHeaderUIElements;
- (nullable NSArray *)accessibilityColumnHeaderUIElements;
 */

- (NSRect)accessibilityFrame {
    return [super accessibilityFrame];
}

- (id)accessibilityParent {
    return [super accessibilityParent];
}

@end
