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

static const char* ACCESSIBLE_JTABLE_NAME = "javax.swing.JTable$AccessibleJTable";

@implementation JavaTableAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxTable";
}

@end

@implementation PlatformAxTable

- (NSArray *)accessibilityRows {
    return [self accessibilityChildren];
}

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilitySelectedRows {
    return [self accessibilitySelectedChildren];
}

- (NSString *)accessibilityLabel {
    return [super accessibilityLabel];
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

- (NSRect)accessibilityFrame {
    return [super accessibilityFrame];
}

- (id)accessibilityParent {
    return [super accessibilityParent];
}

- (int)accessibleRowCount {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [[self javaBase] axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleRowCount, clsInfo, "getAccessibleRowCount", "()I");
    jint javaRowsCount = JNFCallIntMethod(env, [[self javaBase] axContextWithEnv:env], jm_getAccessibleRowCount);
    return (int)javaRowsCount;
}

- (int)accessibleColCount {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [[self javaBase] axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleColumnCount, clsInfo, "getAccessibleColumnCount", "()I");
    jint javaColsCount = JNFCallIntMethod(env, [[self javaBase] axContextWithEnv:env], jm_getAccessibleColumnCount);
    return (int)javaColsCount;
}

- (NSArray<NSNumber *> *)selectedAccessibleRows {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [[self javaBase] axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getSelectedAccessibleRows, clsInfo, "getSelectedAccessibleRows", "()[I");
    jintArray selectidRowNumbers = JNFCallObjectMethod(env, [[self javaBase] axContextWithEnv:env], jm_getSelectedAccessibleRows);
    if (selectidRowNumbers == NULL) {
        return nil;
    }
    jsize arrayLen = (*env)->GetArrayLength(env, selectidRowNumbers);
    jint *indexsis = (*env)->GetIntArrayElements(env, selectidRowNumbers, 0);
    NSMutableArray<NSNumber *> *nsArraySelectidRowNumbers = [NSMutableArray<NSNumber *> arrayWithCapacity:arrayLen];
    for (int i = 0; i < arrayLen; i++) {
        [nsArraySelectidRowNumbers addObject:[NSNumber numberWithInt:indexsis[i]]];
    }
    return [NSArray<NSNumber *> arrayWithArray:nsArraySelectidRowNumbers];
}

- (NSArray<NSNumber *> *)selectedAccessibleColumns {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [[self javaBase] axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getSelectedAccessibleColumns, clsInfo, "getSelectedAccessibleColumns", "()[I");
    jintArray selectidColumnNumbers = JNFCallObjectMethod(env, [[self javaBase] axContextWithEnv:env], jm_getSelectedAccessibleColumns);
    if (selectidColumnNumbers == NULL) {
        return nil;
    }
    jsize arrayLen = (*env)->GetArrayLength(env, selectidColumnNumbers);
    jint *indexsis = (*env)->GetIntArrayElements(env, selectidColumnNumbers, 0);
    NSMutableArray<NSNumber *> *nsArraySelectidColumnNumbers = [NSMutableArray<NSNumber *> arrayWithCapacity:arrayLen];
    for (int i = 0; i < arrayLen; i++) {
        [nsArraySelectidColumnNumbers addObject:[NSNumber numberWithInt:indexsis[i]]];
    }
    return [NSArray<NSNumber *> arrayWithArray:nsArraySelectidColumnNumbers];
}

- (int)accessibleRowAtIndex:(int)index { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [[self javaBase] axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleRowAtIndex, clsInfo, "getAccessibleRowAtIndex", "(I)I");
    jint rowAtIndex = JNFCallIntMethod(env, [[self javaBase] axContextWithEnv:env], jm_getAccessibleRowAtIndex, (jint)index);
    return (int)rowAtIndex;
}

- (int)accessibleColumnAtIndex:(int)index { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [[self javaBase] axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleColumnAtIndex, clsInfo, "getAccessibleColumnAtIndex", "(I)I");
    jint columnAtIndex = JNFCallIntMethod(env, [[self javaBase] axContextWithEnv:env], jm_getAccessibleColumnAtIndex, (jint)index);
    return (int)columnAtIndex;
}

@end
