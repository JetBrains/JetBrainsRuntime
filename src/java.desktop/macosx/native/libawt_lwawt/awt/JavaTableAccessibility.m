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

@implementation JavaTableAccessibility

- (int)accessibleRowCount {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return 0;
    static jclass cls = (*env)->GetObjectClass(env, axContext);
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
    static jclass cls = (*env)->GetObjectClass(env, axContext);
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
    static jclass cls = (*env)->GetObjectClass(env, axContext);
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
    static jclass cls = (*env)->GetObjectClass(env, axContext);
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
    static jclass cls = (*env)->GetObjectClass(env, axContext);
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
    static jclass cls = (*env)->GetObjectClass(env, axContext);
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
    static jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_isAccessibleChildSelected, cls, "isAccessibleChildSelected", "(I)Z", NO);
    jboolean isAccessibleChildSelected = (*env)->CallBooleanMethod(env, axContext, jm_isAccessibleChildSelected, (jint)index);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    return isAccessibleChildSelected;
}

// NSAccessibilityElement protocol methods

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    NSArray *columns = [self accessibilityColumns];
    NSMutableArray *results = [NSMutableArray arrayWithCapacity:[children count] + [columns count]];
    [results addObjectsFromArray:children];
    [results addObjectsFromArray:columns];
    return [NSArray arrayWithArray:results];
}

- (NSArray *)accessibilityRows {
    return [super accessibilityChildren];
}

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilitySelectedRows {
    return [super accessibilitySelectedChildren];
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
