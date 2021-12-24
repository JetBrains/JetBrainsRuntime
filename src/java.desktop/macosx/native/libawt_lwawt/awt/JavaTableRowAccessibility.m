// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import "JavaTableRowAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "JavaTableAccessibility.h"
#import "JavaCellAccessibility.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import "AWTView.h"

// GET* macros defined in JavaAccessibilityUtilities.h, so they can be shared.
static jclass sjc_CAccessibility = NULL;

@implementation JavaTableRowAccessibility

// NSAccessibilityElement protocol methods

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityRowRole;
}

- (NSAccessibilitySubrole)accessibilitySubrole {
    return NSAccessibilityTableRowSubrole;
}

- (NSArray *)accessibilityChildren {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JavaComponentAccessibility *parent = [self accessibilityParent];
    if (parent->fAccessible == NULL) return nil;

    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getTableRowChildrenAndRoles, sjc_CAccessibility, "getTableRowChildrenAndRoles",\
        "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZI)[Ljava/lang/Object;", nil);

    jobjectArray jchildrenAndRoles = (jobjectArray)(*env)->CallStaticObjectMethod(
        env, sjc_CAccessibility, jm_getTableRowChildrenAndRoles, parent->fAccessible, parent->fComponent, JAVA_AX_ALL_CHILDREN, NO, [self rowNumberInTable]);
    CHECK_EXCEPTION();

    if (jchildrenAndRoles == NULL) return nil;

    jsize arrayLen = (*env)->GetArrayLength(env, jchildrenAndRoles);
    NSMutableArray *children = [NSMutableArray arrayWithCapacity:arrayLen / 2];
    int childIndex = [self rowNumberInTable] * [(JavaTableAccessibility *)parent accessibilityColumnCount];

    for (NSInteger i = 0; i < arrayLen; i += 2) {
        jobject /* Accessible */ jchild = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i);
        jobject /* String */ jchildJavaRole = (*env)->GetObjectArrayElement(env, jchildrenAndRoles, i + 1);

        NSString *childJavaRole = nil;
        if (jchildJavaRole != NULL) {
            DECLARE_CLASS_RETURN(sjc_AccessibleRole, "javax/accessibility/AccessibleRole", nil);
            DECLARE_FIELD_RETURN(sjf_key, sjc_AccessibleRole, "key", "Ljava/lang/String;", nil);
            jobject jkey = (*env)->GetObjectField(env, jchildJavaRole, sjf_key);
            childJavaRole = JavaStringToNSString(env, jkey);
            (*env)->DeleteLocalRef(env, jkey);
        }

        JavaCellAccessibility *child = (JavaCellAccessibility *)
            [JavaComponentAccessibility createWithParent:self
                                               withClass:[JavaCellAccessibility class]
                                              accessible:jchild
                                                    role:childJavaRole
                                                   index:childIndex
                                                 withEnv:env
                                                withView:self->fView];
        [children addObject:child];

        (*env)->DeleteLocalRef(env, jchild);
        (*env)->DeleteLocalRef(env, jchildJavaRole);

        childIndex++;
    }
    (*env)->DeleteLocalRef(env, jchildrenAndRoles);

    return children;
}

- (NSInteger)accessibilityIndex {
    return fIndex;
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
        return self->fIndex;
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

