// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "sun_lwawt_macosx_CAccessibility.h"
#import "JavaTableRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTableAccessibility.h"
#import "JavaColumnAccessibility.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import "sun_lwawt_macosx_CAccessible.h"
#import "PropertiesUtilities.h"

static jclass sjc_CAccessibility = NULL;

@implementation JavaTableAccessibility

- (id)getTableInfo:(jint)info
{
    if (fAccessible == NULL) return 0;

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getTableInfo, sjc_CAccessibility, "getTableInfo",
                          "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)I", nil);
    jint count = (*env)->CallStaticIntMethod(env, sjc_CAccessibility, jm_getTableInfo, fAccessible,
                                        fComponent, info);
    CHECK_EXCEPTION();
    NSNumber *index = [NSNumber numberWithInt:count];
    return index;
}

- (NSArray<NSNumber *> *)getTableSelectedInfo:(jint)info
{
    if (fAccessible == NULL) return 0;

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getTableSelectedInfo, sjc_CAccessibility, "getTableSelectedInfo",
                          "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)[I", nil);
    jintArray selected = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, jm_getTableSelectedInfo, fAccessible,
                                        fComponent, info);
    CHECK_EXCEPTION();
    if (selected == NULL) {
        return nil;
    }
    jsize arrayLen = (*env)->GetArrayLength(env, selected);
    jint *indexsis = (*env)->GetIntArrayElements(env, selected, 0);
    NSMutableArray<NSNumber *> *nsArraySelected = [NSMutableArray<NSNumber *> arrayWithCapacity:arrayLen];
    for (int i = 0; i < arrayLen; i++) {
        [nsArraySelected addObject:[NSNumber numberWithInt:indexsis[i]]];
    }
    (*env)->DeleteLocalRef(env, selected);
    return [NSArray<NSNumber *> arrayWithArray:nsArraySelected];
}

// NSAccessibilityElement protocol methods

- (NSInteger)accessibilityRowCount
{
    return [[self getTableInfo:sun_lwawt_macosx_CAccessibility_JAVA_AX_ROWS] integerValue];
}

- (NSInteger)accessibilityColumnCount
{
    return [[self getTableInfo:sun_lwawt_macosx_CAccessibility_JAVA_AX_COLS] integerValue];
}

- (NSArray *)accessibilityChildren {
    return [self accessibilityRows];
}

- (NSArray *)accessibilitySelectedChildren {
    return [self accessibilitySelectedRows];
}

- (NSArray *)accessibilityVisibleChildren {
    return [self accessibilityVisibleRows];
}

- (NSArray *)accessibilityRows {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSString *maxTableAccessibleRowCountProp = [PropertiesUtilities javaSystemPropertyForKey:@"sun.awt.mac.a11y.tableAccessibleRowCountThreshold" withEnv:env];
    NSInteger maxAccessibleTableRowCount = maxTableAccessibleRowCountProp != NULL ? [maxTableAccessibleRowCountProp integerValue] : 0;
    if (maxAccessibleTableRowCount > 0 && [self accessibilityRowCount] > maxAccessibleTableRowCount) {
        return [self accessibilityVisibleRows];
    }
    int rowCount = [self accessibilityRowCount];
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:rowCount];
    for (int i = 0; i < rowCount; i++) {
        [children addObject:[self createRowForIndex:[NSNumber numberWithInt:i]]];
    }

    return children;
}

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilitySelectedRows {
    NSArray *selectedRows = [self getTableSelectedInfo:sun_lwawt_macosx_CAccessibility_JAVA_AX_ROWS];
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:[selectedRows count]];
    for (NSNumber *index in selectedRows) {
        [children addObject:[self createRowForIndex:index]];
    }

    return children;
}

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilityVisibleRows {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(sjm_getTableVisibleRowRange, sjc_CAccessibility, \
     "getTableVisibleRowRange", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)[I", nil);

    jintArray jIndices = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getTableVisibleRowRange,
                                                        self->fAccessible, self->fComponent);
    CHECK_EXCEPTION();

    if (jIndices == NULL) return nil;
    jint *range = (*env)->GetIntArrayElements(env, jIndices, NULL);
    int firstRow = range[0];
    int lastRow = range[1];
    (*env)->ReleaseIntArrayElements(env, jIndices, range, 0);
    if (firstRow < 0 || lastRow < 0) return nil;

    NSMutableArray *children = [NSMutableArray arrayWithCapacity:lastRow - firstRow + 1];
    for (int i = firstRow; i <= lastRow; i++) {
        [children addObject:[self createRowForIndex:[NSNumber numberWithInt:i]]];
    }
    return children;
}

- (NSString *)accessibilityLabel {
    return [super accessibilityLabel] == NULL ? @"table" : [super accessibilityLabel];
}

- (NSRect)accessibilityFrame {
    return [super accessibilityFrame];
}

- (id)accessibilityParent {
    return [super accessibilityParent];
}

- (void)dealloc
{
    [self clearCache];
    [super dealloc];
}

- (JavaTableAccessibility *)createRowForIndex:(NSNumber *)index {
    if (rowCache == nil) {
        int rowCount = [self accessibilityRowCount];
        rowCache = [[NSMutableDictionary<NSNumber*, id> dictionaryWithCapacity:rowCount] retain];
    }

    id row = [rowCache objectForKey:index];
    if (row == nil) {
        row = [[JavaTableRowAccessibility alloc] initWithParent:self
                                                        withEnv:[ThreadUtilities getJNIEnv]
                                                 withAccessible:NULL
                                                      withIndex:index.intValue
                                                       withView:[self view]
                                                   withJavaRole:JavaAccessibilityIgnore];
        [rowCache setObject:row forKey:index];
    }

    return row;
}

- (void)clearCache {
    for (NSNumber *key in [rowCache allKeys]) {
        [[rowCache objectForKey:key] release];
    }
    [rowCache release];
    rowCache = nil;
}

/*
- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilityVisibleRows;
- (nullable NSArray *)accessibilityColumns;
- (nullable NSArray *)accessibilitySelectedColumns;
- (nullable NSArray *)accessibilityVisibleColumns;

 - (nullable NSArray *)accessibilitySelectedCells;
- (nullable NSArray *)accessibilityVisibleCells;
- (nullable NSArray *)accessibilityRowHeaderUIElements;
- (nullable NSArray *)accessibilityColumnHeaderUIElements;
 */

@end

/*
 * Class:     sun_lwawt_macosx_CAccessible
 * Method:    tableContentIndexDestroy
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_tableContentCacheClear
(JNIEnv *env, jclass class, jlong element)
{
    JNI_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(clearCache)
                                      on:(JavaComponentAccessibility *)jlong_to_ptr(element)
                              withObject:nil
                           waitUntilDone:NO];
    JNI_COCOA_EXIT(env);
}
