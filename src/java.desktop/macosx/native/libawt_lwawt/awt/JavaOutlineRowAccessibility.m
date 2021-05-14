// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>
#import "JavaOutlineRowAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"

static JNF_CLASS_CACHE(sjc_CAccessible, "sun/lwawt/macosx/CAccessible");
static JNF_STATIC_MEMBER_CACHE(sjm_getCAccessible, sjc_CAccessible, "getCAccessible", "(Ljavax/accessibility/Accessible;)Lsun/lwawt/macosx/CAccessible;");

@implementation JavaOutlineRowAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxOutlineRow";
}

@synthesize accessibleLevel;

- (jobject) currentAccessibleWithENV:(JNIEnv *)env {
    jobject jAxContext = getAxContext(env, fAccessible, fComponent);
    if (jAxContext == NULL) return nil;
    JNFClassInfo clsInfo;
    clsInfo.name = [JNFObjectClassName(env, jAxContext) UTF8String];
    clsInfo.cls = (*env)->GetObjectClass(env, jAxContext);
    JNF_MEMBER_CACHE(jm_getCurrentComponent, clsInfo, "getCurrentComponent", "()Ljava/awt/Component;");
    jobject newComponent = JNFCallObjectMethod(env, jAxContext, jm_getCurrentComponent);
    (*env)->DeleteLocalRef(env, jAxContext);
    if (newComponent != NULL) {
        jobject newAccessible = JNFCallStaticObjectMethod(env, sjm_getCAccessible, newComponent);
        (*env)->DeleteLocalRef(env, newComponent);
        if (newAccessible != NULL) {
            return newAccessible;
        } else {
            return NULL;
        }
    } else {
        return NULL;
    }
}

@end

@implementation PlatformAxOutlineRow

- (NSArray *)accessibilityChildren {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject currentAccessible = [(JavaOutlineRowAccessibility *) [self javaBase] currentAccessibleWithENV:env];
    if (currentAccessible == NULL) {
        return nil;
    }
    JavaBaseAccessibility *currentElement = [JavaBaseAccessibility createWithAccessible:currentAccessible withEnv:env withView:[[self javaBase] view] isCurrent:YES];
    NSArray *children = [JavaBaseAccessibility childrenOfParent:currentElement withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:YES];
    if ([children count] == 0) {
        return [NSArray arrayWithObject:[JavaBaseAccessibility createWithParent:[self javaBase] accessible:[[self javaBase] accessible] role:[[self javaBase] javaRole] index:[[self javaBase] index] withEnv:env withView:[[self javaBase] view] isWrapped:YES].platformAxElement];
    } else {
        return children;
    }
}

- (NSInteger)accessibilityDisclosureLevel {
    return [(JavaOutlineRowAccessibility *) [self javaBase] accessibleLevel];
}

- (BOOL)isAccessibilityDisclosed {
    return isExpanded([ThreadUtilities getJNIEnv], [[self javaBase] axContextWithEnv:[ThreadUtilities getJNIEnv]], [[self javaBase] component]);
}

- (NSAccessibilitySubrole)accessibilitySubrole {
    return NSAccessibilityOutlineRowSubrole;;
}

- (NSAccessibilityRole)accessibilityRole {
    return NSAccessibilityRowRole;;
}

@end
