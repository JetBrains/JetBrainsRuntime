// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>
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

@implementation JavaTableAccessibility

static jclass sjc_CAccessibility = NULL;

- (int)accessibleRowCount {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return 0;
    jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_getAccessibleRowCount, cls, "getAccessibleRowCount", "()I", 0);
    jint javaRowsCount = (*env)->CallIntMethod(env, axContext, jm_getAccessibleRowCount);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    return (int)javaRowsCount;
}

- (int)accessibleColCount {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return 0;
    jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_getAccessibleColumnCount, cls, "getAccessibleColumnCount", "()I", 0);
    jint javaColsCount = (*env)->CallIntMethod(env, axContext, jm_getAccessibleColumnCount);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    return (int)javaColsCount;
}

- (NSArray<NSNumber *> *)selectedAccessibleRows {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return nil;
    jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_getSelectedAccessibleRows, cls, "getSelectedAccessibleRows", "()[I", nil);
    jintArray selectidRowNumbers = (*env)->CallObjectMethod(env, axContext, jm_getSelectedAccessibleRows);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    if (selectidRowNumbers == NULL) {
        return nil;
    }
    jsize arrayLen = (*env)->GetArrayLength(env, selectidRowNumbers);
    jint *indexsis = (*env)->GetIntArrayElements(env, selectidRowNumbers, 0);
    NSMutableArray<NSNumber *> *nsArraySelectidRowNumbers = [NSMutableArray<NSNumber *> arrayWithCapacity:arrayLen];
    for (int i = 0; i < arrayLen; i++) {
        [nsArraySelectidRowNumbers addObject:[NSNumber numberWithInt:indexsis[i]]];
    }
    (*env)->DeleteLocalRef(env, selectidRowNumbers);
    return [NSArray<NSNumber *> arrayWithArray:nsArraySelectidRowNumbers];
}

- (NSArray<NSNumber *> *)selectedAccessibleColumns {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return nil;
    jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_getSelectedAccessibleColumns, cls, "getSelectedAccessibleColumns", "()[I", nil);
    jintArray selectidColumnNumbers = (*env)->CallObjectMethod(env, axContext, jm_getSelectedAccessibleColumns);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    if (selectidColumnNumbers == NULL) {
        return nil;
    }
    jsize arrayLen = (*env)->GetArrayLength(env, selectidColumnNumbers);
    jint *indexsis = (*env)->GetIntArrayElements(env, selectidColumnNumbers, 0);
    NSMutableArray<NSNumber *> *nsArraySelectidColumnNumbers = [NSMutableArray<NSNumber *> arrayWithCapacity:arrayLen];
    for (int i = 0; i < arrayLen; i++) {
        [nsArraySelectidColumnNumbers addObject:[NSNumber numberWithInt:indexsis[i]]];
    }
    (*env)->DeleteLocalRef(env, selectidColumnNumbers);
    return [NSArray<NSNumber *> arrayWithArray:nsArraySelectidColumnNumbers];
}

- (int)accessibleRowAtIndex:(int)index {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return 0;
    jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_getAccessibleRowAtIndex, cls, "getAccessibleRowAtIndex", "(I)I", 0);
    jint rowAtIndex = (*env)->CallIntMethod(env, axContext, jm_getAccessibleRowAtIndex, (jint)index);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    return (int)rowAtIndex;
}

- (int)accessibleColumnAtIndex:(int)index {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return 0;
    jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_getAccessibleColumnAtIndex, cls, "getAccessibleColumnAtIndex", "(I)I", 0);
    jint columnAtIndex = (*env)->CallIntMethod(env, axContext, jm_getAccessibleColumnAtIndex, (jint)index);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    return (int)columnAtIndex;
}

- (BOOL) isAccessibleChildSelectedFromIndex:(int)index {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return NO;
    jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_isAccessibleChildSelected, cls, "isAccessibleChildSelected", "(I)Z", NO);
    jboolean isAccessibleChildSelected = (*env)->CallBooleanMethod(env, axContext, jm_isAccessibleChildSelected, (jint)index);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    return isAccessibleChildSelected;
}

// NSAccessibilityElement protocol methods

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
    if (maxAccessibleTableRowCount > 0 && [self accessibleRowCount] > maxAccessibleTableRowCount) {
        return [self accessibilityVisibleRows];
    }
    int rowCount = [self accessibleRowCount];
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:rowCount];
    for (int i = 0; i < rowCount; i++) {
        [children addObject:[self createRowForIndex:[NSNumber numberWithInt:i]]];
    }

    return children;
}

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilitySelectedRows {
    NSArray *selectedRows = [self selectedAccessibleRows];
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
        int rowCount = [self accessibleRowCount];
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
    JavaComponentAccessibility *obj = (JavaComponentAccessibility *)jlong_to_ptr(element);
    if ([obj respondsToSelector:@selector(clearCache)]) {
        [ThreadUtilities performOnMainThread:@selector(clearCache)
                                          on:obj
                                  withObject:nil
                               waitUntilDone:NO];
    }
    JNI_COCOA_EXIT(env);
}
