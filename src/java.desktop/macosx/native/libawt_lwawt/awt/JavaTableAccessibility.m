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

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");

static const char* ACCESSIBLE_JTABLE_NAME = "javax.swing.JTable$AccessibleJTable";

@implementation JavaTableAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxTable";
}

- (int)accessibleRowCount {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleRowCount, clsInfo, "getAccessibleRowCount", "()I");
    jint javaRowsCount = JNFCallIntMethod(env, [self axContextWithEnv:env], jm_getAccessibleRowCount);
    return (int)javaRowsCount;
}

- (int)accessibleColCount {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleColumnCount, clsInfo, "getAccessibleColumnCount", "()I");
    jint javaColsCount = JNFCallIntMethod(env, [self axContextWithEnv:env], jm_getAccessibleColumnCount);
    return (int)javaColsCount;
}

- (NSArray<NSNumber *> *)selectedAccessibleRows {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getSelectedAccessibleRows, clsInfo, "getSelectedAccessibleRows", "()[I");
    jintArray selectidRowNumbers = JNFCallObjectMethod(env, [self axContextWithEnv:env], jm_getSelectedAccessibleRows);
    if (selectidRowNumbers == NULL) {
        (*env)->DeleteLocalRef(env, selectidRowNumbers);
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
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getSelectedAccessibleColumns, clsInfo, "getSelectedAccessibleColumns", "()[I");
    jintArray selectidColumnNumbers = JNFCallObjectMethod(env, [self axContextWithEnv:env], jm_getSelectedAccessibleColumns);
    if (selectidColumnNumbers == NULL) {
        (*env)->DeleteLocalRef(env, selectidColumnNumbers);
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
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleRowAtIndex, clsInfo, "getAccessibleRowAtIndex", "(I)I");
    jint rowAtIndex = JNFCallIntMethod(env, [self axContextWithEnv:env], jm_getAccessibleRowAtIndex, (jint)index);
    return (int)rowAtIndex;
}

- (int)accessibleColumnAtIndex:(int)index {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleColumnAtIndex, clsInfo, "getAccessibleColumnAtIndex", "(I)I");
    jint columnAtIndex = JNFCallIntMethod(env, [self axContextWithEnv:env], jm_getAccessibleColumnAtIndex, (jint)index);
    return (int)columnAtIndex;
}

- (BOOL) isAccessibleChildSelectedFromIndex:(int)index {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JTABLE_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_isAccessibleChildSelected, clsInfo, "isAccessibleChildSelected", "(I)Z");
    jboolean isAccessibleChildSelected = JNFCallIntMethod(env, [self axContextWithEnv:env], jm_isAccessibleChildSelected, (jint)index);
    return isAccessibleChildSelected;
}

@end

@implementation PlatformAxTable

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
    int colCount = [(JavaTableAccessibility *)[self javaBase] accessibleColCount];
    NSMutableArray *columns = [NSMutableArray arrayWithCapacity:colCount];
    for (int i = 0; i < colCount; i++) {
        [columns addObject:[[JavaColumnAccessibility alloc] initWithParent:[self javaBase]
                                                                   withEnv:[ThreadUtilities getJNIEnv]
                                                            withAccessible:NULL
                                                                 withIndex:i
                                                                  withView:[[self javaBase] view]
                                                              withJavaRole:JavaAccessibilityIgnore].platformAxElement];
    }
    return [NSArray arrayWithArray:columns];
}

- (nullable NSArray *)accessibilitySelectedColumns {
    NSArray<NSNumber *> *indexes = [(JavaTableAccessibility *)[self javaBase] selectedAccessibleColumns];
    NSMutableArray *columns = [NSMutableArray arrayWithCapacity:[indexes count]];
    for (NSNumber *i in indexes) {
        [columns addObject:[[JavaColumnAccessibility alloc] initWithParent:[self javaBase]
                                                                   withEnv:[ThreadUtilities getJNIEnv]
                                                            withAccessible:NULL
                                                                 withIndex:i.unsignedIntValue
                                                                  withView:[[self javaBase] view]
                                                              withJavaRole:JavaAccessibilityIgnore].platformAxElement];
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
