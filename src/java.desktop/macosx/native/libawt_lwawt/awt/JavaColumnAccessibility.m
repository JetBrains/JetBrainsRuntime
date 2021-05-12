// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaCellAccessibility.h"
#import "JavaColumnAccessibility.h"
#import "JavaTableAccessibility.h"
#import "ThreadUtilities.h"

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");

@implementation JavaColumnAccessibility

// NSAccessibilityElement protocol methods

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityColumnRole;
}

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        JNIEnv *env = [ThreadUtilities getJNIEnv];
        JavaComponentAccessibility *parent = [self accessibilityParent];
        if (parent->fAccessible == NULL) return nil;
        jobjectArray jchildrenAndRoles = (jobjectArray)JNFCallStaticObjectMethod(env, jm_getChildrenAndRoles, parent->fAccessible, parent->fComponent, JAVA_AX_ALL_CHILDREN, NO);
        if (jchildrenAndRoles == NULL) return nil;

        jsize arrayLen = (*env)->GetArrayLength(env, jchildrenAndRoles);
        NSMutableArray *childrenCells = [NSMutableArray arrayWithCapacity:arrayLen/2];

        NSUInteger childIndex = [self columnNumberInTable];

        int inc = [(JavaTableAccessibility *)[self accessibilityParent] accessibleColCount] * 2;
        NSInteger i = childIndex * 2;
        for(NSInteger i; i < arrayLen; i += inc)
        {
            jobject /* Accessible */ jchild = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i);
            jobject /* String */ jchildJavaRole = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i+1);

            NSString *childJavaRole = nil;
            if (jchildJavaRole != NULL) {
                jobject jkey = JNFGetObjectField(env, jchildJavaRole, sjf_key);
                childJavaRole = JNFJavaToNSString(env, jkey);
                (*env)->DeleteLocalRef(env, jkey);
            }

            JavaCellAccessibility *child = [[JavaCellAccessibility alloc] initWithParent:self
                                                                                 withEnv:env
                                                                          withAccessible:jchild
                                                                               withIndex:childIndex
                                                                                withView:self->fView
                                                                            withJavaRole:childJavaRole];
            [childrenCells addObject:[child autorelease]];

            (*env)->DeleteLocalRef(env, jchild);
            (*env)->DeleteLocalRef(env, jchildJavaRole);

            childIndex += (inc / 2);
        }
        (*env)->DeleteLocalRef(env, jchildrenAndRoles);
        return childrenCells;
    } else {
        return children;
    }
}

- (NSUInteger)columnNumberInTable {
    return self->fIndex;
}

@end
