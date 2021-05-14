// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import "JavaOutlineRowAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"

static JNF_CLASS_CACHE(sjc_CAccessible, "sun/lwawt/macosx/CAccessible");
static JNF_STATIC_MEMBER_CACHE(sjm_getCAccessible, sjc_CAccessible, "getCAccessible", "(Ljavax/accessibility/Accessible;)Lsun/lwawt/macosx/CAccessible;");

@implementation JavaOutlineRowAccessibility

@synthesize accessibleLevel;

// NSAccessibilityElement protocol methods

- (NSArray *)accessibilityChildren {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject jAxContext = getAxContext(env, fAccessible, fComponent);
    if (jAxContext == NULL) return nil;
    JNFClassInfo clsInfo;
    clsInfo.name = [JNFObjectClassName(env, jAxContext) UTF8String];
    clsInfo.cls = (*env)->GetObjectClass(env, jAxContext);
    JNF_MEMBER_CACHE(jm_getCurrentComponent, clsInfo, "getCurrentComponent", "()Ljava/awt/Component;");
    jobject newComponent = JNFCallObjectMethod(env, jAxContext, jm_getCurrentComponent);
    (*env)->DeleteLocalRef(env, jAxContext);
    jobject currentAccessible = NULL;
    if (newComponent != NULL) {
        currentAccessible = JNFCallStaticObjectMethod(env, sjm_getCAccessible, newComponent);
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
