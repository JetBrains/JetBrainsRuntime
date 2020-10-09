// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTextAccessibility.h"
#import "JavaListAccessibility.h"
#import "JavaTableAccessibility.h"
#import "JavaCellAccessibility.h"
#import "ThreadUtilities.h"

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");

@implementation JavaRowAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxRow";
}

@end

@implementation PlatformAxRow

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        if ([self IsListRow]) {
        NSString *javaRole = [[self javaBase] javaRole];
        JavaBaseAccessibility *newChild = nil;
        if ([javaRole isEqualToString:@"pagetablist"]) {
            newChild = [TabGroupAccessibility alloc];
        } else if ([javaRole isEqualToString:@"scrollpane"]) {
            newChild = [ScrollAreaAccessibility alloc];
        } else {
            NSString *nsRole = [sRoles objectForKey:javaRole];
            if ([nsRole isEqualToString:NSAccessibilityStaticTextRole] || [nsRole isEqualToString:NSAccessibilityTextAreaRole] || [nsRole isEqualToString:NSAccessibilityTextFieldRole]) {
                newChild = [JavaTextAccessibility alloc];
            } else if ([nsRole isEqualToString:NSAccessibilityListRole]) {
                newChild = [JavaListAccessibility alloc];
            } else {
                newChild = [JavaComponentAccessibility alloc];
            }
        }
        [newChild initWithParent:[self javaBase]
                         withEnv:[ThreadUtilities getJNIEnv]
                  withAccessible:[[self javaBase] accessible]
                       withIndex:[[self javaBase] index]
                        withView:[[self javaBase] view]
                    withJavaRole:javaRole];
        return [NSArray arrayWithObject:[newChild autorelease]];
        } else if ([self isTableRow]) {
            JNIEnv *env = [ThreadUtilities getJNIEnv];
            if ([[[self accessibilityParent] javaBase] accessible] == NULL) return nil;
            jobjectArray jchildrenAndRoles = (jobjectArray)JNFCallStaticObjectMethod(env, jm_getChildrenAndRoles, [[[self accessibilityParent] javaBase] accessible], [[[self accessibilityParent] javaBase] component], JAVA_AX_ALL_CHILDREN, NO);
            if (jchildrenAndRoles == NULL) return nil;

            jsize arrayLen = (*env)->GetArrayLength(env, jchildrenAndRoles);
            NSMutableArray *childrenCells = [NSMutableArray arrayWithCapacity:arrayLen/2];

            NSUInteger childIndex = [self rowNumberInTable] * [[self accessibilityParent] accessibleColCount];
            NSInteger i = childIndex * 2;
            NSInteger n = ([self rowNumberInTable] + 1) * [[self accessibilityParent] accessibleColCount] * 2;
            JavaRowAccessibility *selfRow = [self javaBase];
            for(i; i < n; i+=2)
            {
                jobject /* Accessible */ jchild = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i);
                jobject /* String */ jchildJavaRole = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i+1);

                NSString *childJavaRole = nil;
                if (jchildJavaRole != NULL) {
                    jobject jkey = JNFGetObjectField(env, jchildJavaRole, sjf_key);
                    childJavaRole = JNFJavaToNSString(env, jkey);
                    (*env)->DeleteLocalRef(env, jkey);
                }

                    JavaCellAccessibility *child = [[JavaCellAccessibility alloc] initWithParent:selfRow
                                                                                         withEnv:env
                                                                                  withAccessible:jchild
                                                                                       withIndex:childJavaRole
                                                                                        withView:[selfRow view]
                                                                                    withJavaRole:childJavaRole];
                    [childrenCells addObject:[child autorelease].platformAxElement];

                (*env)->DeleteLocalRef(env, jchild);
                (*env)->DeleteLocalRef(env, jchildJavaRole);

                        childIndex++;
            }
            (*env)->DeleteLocalRef(env, jchildrenAndRoles);
            return childrenCells;
        }
        return nil;
    } else {
        return children;
    }
}

- (NSInteger)accessibilityIndex {
    return [[self accessibilityParent] accessibilityIndexOfChild:self];
}

- (NSString *)accessibilityLabel {
    if ([self isTableRow]) {
        NSString *accessibilityName = [NSMutableString string];
        for (id cell in [self accessibilityChildren]) {
            if ([accessibilityName isEqualToString:@""]) {
                accessibilityName = [cell accessibilityLabel];
            } else {
                accessibilityName = [accessibilityName stringByAppendingFormat:@", %@", [cell accessibilityLabel]];
            }
        }
        return accessibilityName;
    } else {
        return [super accessibilityLabel];
    }
}

// to avoid warning (why?): method in protocol 'NSAccessibilityElement' not implemented
- (NSRect)accessibilityFrame
{
    return [super accessibilityFrame];
}

// to avoid warning (why?): method in protocol 'NSAccessibilityElement' not implemented
- (id)accessibilityParent
{
    return [super accessibilityParent];
}

- (bool)isTableRow {
    return [[[self accessibilityParent] accessibilityRole] isEqualToString:NSAccessibilityTableRole];
}

- (bool)IsListRow {
    return [[[self accessibilityParent] accessibilityRole] isEqualToString:NSAccessibilityListRole];
}

- (NSUInteger)rowNumberInTable {
    if ([self isTableRow]) {
        return [[self accessibilityParent] accessibleRowAtIndex:[[self accessibilityParent] accessibilityIndexOfChild:self]];
    } else if ([self IsListRow]) {
        return [[self accessibilityParent] accessibilityIndexOfChild:self];
    }
}

@end
