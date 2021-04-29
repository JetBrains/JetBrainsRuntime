// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaTableRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTableAccessibility.h"
#import "JavaCellAccessibility.h"
#import "ThreadUtilities.h"

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");

@implementation JavaTableRowAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxTableRow";
}

@end

@implementation PlatformAxTableRow

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityRowRole;
}

- (NSAccessibilitySubrole)accessibilitySubrole {
    return NSAccessibilityTableRowSubrole;
}

- (NSArray *)accessibilityChildren {
    NSArray *children = [super accessibilityChildren];
    if (children == NULL) {
        JNIEnv *env = [ThreadUtilities getJNIEnv];
        if ([[[self accessibilityParent] javaComponent] accessible] == NULL) return nil;
        jobjectArray jchildrenAndRoles = (jobjectArray)JNFCallStaticObjectMethod(env, jm_getChildrenAndRoles, [[[self accessibilityParent] javaComponent] accessible], [[[self accessibilityParent] javaComponent] component], JAVA_AX_ALL_CHILDREN, NO);
        if (jchildrenAndRoles == NULL) return nil;

        jsize arrayLen = (*env)->GetArrayLength(env, jchildrenAndRoles);
        NSMutableArray *childrenCells = [NSMutableArray arrayWithCapacity:arrayLen/2];

        NSUInteger childIndex = [self rowNumberInTable] * [(JavaTableAccessibility *) [[self accessibilityParent] javaComponent] accessibleColCount];
        NSInteger i = childIndex * 2;
        NSInteger n = ([self rowNumberInTable] + 1) * [(JavaTableAccessibility *) [[self accessibilityParent] javaComponent] accessibleColCount] * 2;
        JavaTableRowAccessibility *selfRow = [self javaComponent];
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
                                                                               withIndex:childIndex
                                                                                withView:[selfRow view]
                                                                            withJavaRole:childJavaRole];
            [childrenCells addObject:[child autorelease].platformAxElement];

            (*env)->DeleteLocalRef(env, jchild);
            (*env)->DeleteLocalRef(env, jchildJavaRole);

            childIndex++;
        }
        (*env)->DeleteLocalRef(env, jchildrenAndRoles);
        return childrenCells;
    } else {
        return children;
    }
}

- (NSInteger)accessibilityIndex {
    return [[self accessibilityParent] accessibilityIndexOfChild:self];
}

- (NSString *)accessibilityLabel {
    NSString *accessibilityName = @"";
        for (id cell in [self accessibilityChildren]) {
            if ([accessibilityName isEqualToString:@""]) {
                accessibilityName = [cell accessibilityLabel];
            } else {
                accessibilityName = [accessibilityName stringByAppendingFormat:@", %@", [cell accessibilityLabel]];
            }
        }
        return accessibilityName;
}

- (id)accessibilityParent
{
    return [super accessibilityParent];
}

- (NSUInteger)rowNumberInTable {
        return [[self javaComponent] index];
}

- (NSRect)accessibilityFrame {
        int height = [[[self accessibilityChildren] objectAtIndex:0] accessibilityFrame].size.height;
        int width = 0;
        NSPoint point = [[[self accessibilityChildren] objectAtIndex:0] accessibilityFrame].origin;
        for (id cell in [self accessibilityChildren]) {
            width += [cell accessibilityFrame].size.width;
        }
        return NSMakeRect(point.x, point.y, width, height);
}

@end

