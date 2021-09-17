// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import "sun_lwawt_macosx_CAccessibility.h"
#import "JavaTableRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTableAccessibility.h"
#import "JavaCellAccessibility.h"
#import "JavaColumnAccessibility.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import "sun_lwawt_macosx_CAccessible.h"

@implementation JavaTableAccessibility

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

- (NSArray *)accessibilityRows {
    if (rowCash == nil) {
        [self createCash];
    }

    NSArray *keys = [[rowCash allKeys] sortedArrayUsingSelector:@selector(compare:)];
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:[keys count]];
    for (NSNumber *key in keys) {
        [children addObject:[rowCash objectForKey:key]];
    }

    return children;
}

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilitySelectedRows {
    if (rowCash == nil) {
        [self createCash];
    }

    NSArray *selectedRows = [self selectedAccessibleRows];
    NSMutableArray *result = [NSMutableArray arrayWithCapacity:[selectedRows count]];
    for (NSNumber *index in selectedRows) {
        [result addObject:[rowCash objectForKey:index]];
    }

    return result;
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

- (nullable NSArray *)accessibilityColumns {
    int colCount = [self accessibleColCount];
    NSMutableArray *columns = [NSMutableArray arrayWithCapacity:colCount];
    for (int i = 0; i < colCount; i++) {
        [columns addObject:[[JavaColumnAccessibility alloc] initWithParent:self
                                                                   withEnv:[ThreadUtilities getJNIEnv]
                                                            withAccessible:NULL
                                                                 withIndex:i
                                                                  withView:self->fView
                                                              withJavaRole:JavaAccessibilityIgnore]];
    }
    return [NSArray arrayWithArray:columns];
}

- (nullable NSArray *)accessibilitySelectedColumns {
    NSArray<NSNumber *> *indexes = [self selectedAccessibleColumns];
    NSMutableArray *columns = [NSMutableArray arrayWithCapacity:[indexes count]];
    for (NSNumber *i in indexes) {
        [columns addObject:[[JavaColumnAccessibility alloc] initWithParent:self
                                                                   withEnv:[ThreadUtilities getJNIEnv]
                                                            withAccessible:NULL
                                                                 withIndex:i.unsignedIntValue
                                                                  withView:self->fView
                                                              withJavaRole:JavaAccessibilityIgnore]];
    }
    return [NSArray arrayWithArray:columns];
}

- (void)dealloc
{
    [self disposeCash];
    [super dealloc];
}

- (void)createCash {
    int rowCount = [self accessibleRowCount];
    rowCash = [[NSMutableDictionary<NSNumber*, id> dictionaryWithCapacity:rowCount] retain];
    for (int i = 0; i < rowCount; i++) {
        [rowIndex setValue:[[JavaTableRowAccessibility alloc] initWithParent:self
                                                                     withEnv:[ThreadUtilities getJNIEnv]
                                                              withAccessible:NULL
                                                                   withIndex:i
                                                                    withView:[self view]
                                                                withJavaRole:JavaAccessibilityIgnore] forKey:[NSNumber numberWithInt:i]];
    }
}

- (void)disposeCash {
    [rowCash removeAllObjects];
    [rowCash release];
    rowCash = nil;
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
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CAccessible_tableContentIndexDispose
  (JNIEnv *env, jclass class, jlong element)
{
    JNI_COCOA_ENTER(env);
    [ThreadUtilities performOnMainThread:@selector(disposeCash)
    on:(JavaComponentAccessibility *)jlong_to_ptr(element)
    withObject:nil
            waitUntilDone:NO];
    JNI_COCOA_EXIT(env);
}
