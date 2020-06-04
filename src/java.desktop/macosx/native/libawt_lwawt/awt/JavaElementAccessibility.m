// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaElementAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"

static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");

static void RaiseMustOverrideException(NSString *method)
{
    @throw [NSException exceptionWithName:NSInternalInconsistencyException
                                   reason:[NSString stringWithFormat:@"You must override %@ in a subclass", method]
                                 userInfo:nil];
};

@implementation JavaElementAccessibility

- (NSString *)getPlatformAxElementClassName
{
    RaiseMustOverrideException(@"getPlatformAxElementClassName");
    return NULL;
}

@end

@implementation PlatformAxElement

@synthesize javaBase;

- (BOOL)isAccessibilityElement
{
    return YES;
}

- (NSString *)accessibilityLabel
{
    //   RaiseMustOverrideException(@"accessibilityLabel");
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axName = JNFCallStaticObjectMethod(env, sjm_getAccessibleName, [javaBase accessible], [javaBase component]);
    NSString* str = JNFJavaToNSString(env, axName);
    (*env)->DeleteLocalRef(env, axName);
    return str;
}

- (NSArray *)accessibilityChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaBaseAccessibility childrenOfParent:self.javaBase
                                                        withEnv:env
                                               withChildrenCode:JAVA_AX_ALL_CHILDREN
                                                   allowIgnored:[[self accessibilityRole] isEqualToString:NSAccessibilityListRole]];
    if ([children count] > 0) {
        return children;
    }
    return NULL;
}

- (NSArray *)accessibilitySelectedChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *selectedChildren = [JavaBaseAccessibility childrenOfParent:self.javaBase
                                                                withEnv:env
                                                       withChildrenCode:JAVA_AX_SELECTED_CHILDREN
                                                           allowIgnored:[[self accessibilityRole] isEqualToString:NSAccessibilityListRole]];
    if ([selectedChildren count] > 0) {
        return selectedChildren;
    }
    return NULL;
}

- (NSRect)accessibilityFrame
{
    return [self.javaBase getBounds];
}

- (id)accessibilityParent
{
    JavaBaseAccessibility *parent = (JavaBaseAccessibility *) [self.javaBase parent];
    return parent.platformAxElement;
}

- (BOOL)accessibilityIsIgnored
{
    RaiseMustOverrideException(@"accessibilityIsIgnored");
    return NO;
}

- (BOOL)isAccessibilityEnabled
{
    RaiseMustOverrideException(@"isAccessibilityEnabled");
    return YES;
}

- (id)accessibilityApplicationFocusedUIElement
{
    return [self.javaBase getFocusedElement];
}

- (id)getAccessibilityWindow
{
    return [self.javaBase window];
}

@end
