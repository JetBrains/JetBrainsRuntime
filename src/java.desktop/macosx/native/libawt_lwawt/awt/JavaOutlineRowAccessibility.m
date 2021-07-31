// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import "JavaOutlineRowAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"

@implementation JavaOutlineRowAccessibility

@synthesize accessibleLevel;

// NSAccessibilityElement protocol methods

- (NSArray *)accessibilityChildren {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject jAxContext = getAxContext(env, fAccessible, fComponent);
    if (jAxContext == NULL) return nil;
    jclass cls = (*env)->GetObjectClass(env, jAxContext);
    DECLARE_METHOD_RETURN(jm_getCurrentComponent, cls, "getCurrentComponent", "()Ljava/awt/Component;", nil);
    jobject newComponent = (*env)->CallObjectMethod(env, jAxContext, jm_getCurrentComponent);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, jAxContext);
    jobject currentAccessible = NULL;
    if (newComponent != NULL) {
        DECLARE_CLASS_RETURN(sjc_CAccessible, "sun/lwawt/macosx/CAccessible", nil);
        DECLARE_STATIC_METHOD_RETURN(sjm_getCAccessible, sjc_CAccessible, "getCAccessible", "(Ljavax/accessibility/Accessible;)Lsun/lwawt/macosx/CAccessible;", nil);
        currentAccessible =  (*env)->CallStaticObjectMethod(env, sjc_CAccessible, sjm_getCAccessible, newComponent);
        CHECK_EXCEPTION();
        (*env)->DeleteLocalRef(env, newComponent);
    } else {
        return nil;
    }
    if (currentAccessible == NULL) {
        return nil;
    }
    JavaComponentAccessibility *currentElement = [JavaComponentAccessibility createWithAccessible:currentAccessible withEnv:env withView:self->fView isCurrent:YES];
    NSArray *children = [JavaComponentAccessibility childrenOfParent:currentElement withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:YES];
    if ([children count] == 0) {
        return [NSArray arrayWithObject:[JavaComponentAccessibility createWithParent:self
                                                                          accessible:self->fAccessible
                                                                                role:self->fJavaRole
                                                                               index:self->fIndex
                                                                             withEnv:env
                                                                            withView:self->fView
                                                                           isWrapped:YES]];
    } else {
        return children;
    }
}

- (NSInteger)accessibilityDisclosureLevel {
    return [self accessibleLevel];
}

- (BOOL)isAccessibilityDisclosed {
    return isExpanded([ThreadUtilities getJNIEnv], [self axContextWithEnv:[ThreadUtilities getJNIEnv]], self->fComponent);
}

- (NSAccessibilitySubrole)accessibilitySubrole {
    return NSAccessibilityOutlineRowSubrole;;
}

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityRowRole;;
}

@end
