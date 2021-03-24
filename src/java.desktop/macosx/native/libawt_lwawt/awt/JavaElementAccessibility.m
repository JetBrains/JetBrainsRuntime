// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaElementAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"

static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleDescription, sjc_CAccessibility, "getAccessibleDescription", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");

static JNF_CLASS_CACHE(sjc_CAccessible, "sun/lwawt/macosx/CAccessible");
static JNF_MEMBER_CACHE(jm_getAccessibleContext, sjc_CAccessible, "getAccessibleContext", "()Ljavax/accessibility/AccessibleContext;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleIndexInParent, sjc_CAccessibility, "getAccessibleIndexInParent", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)I");

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

- (int)accessibleIndexOfParent {
    return (int)JNFCallStaticIntMethod    ([ThreadUtilities getJNIEnv], sjm_getAccessibleIndexInParent, fAccessible, fComponent);
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

- (NSString *)accessibilityHelp {
    //   RaiseMustOverrideException(@"accessibilityLabel");
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axName = JNFCallStaticObjectMethod(env, sjm_getAccessibleDescription, [javaBase accessible], [javaBase component]);
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
                                                   allowIgnored:([[self accessibilityRole] isEqualToString:NSAccessibilityListRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityTableRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole])
                                                      recursive:[[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole]];
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
                                                           allowIgnored:([[self accessibilityRole] isEqualToString:NSAccessibilityListRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityTableRole] || [[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole])
                                                              recursive:[[self accessibilityRole] isEqualToString:NSAccessibilityOutlineRole]];
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
    id parent = [self.javaBase parent];
    // Checking for protocol compliance can slow down at runtime. See: https://developer.apple.com/documentation/objectivec/nsobject/1418893-conformstoprotocol?language=objc
    return [parent respondsToSelector:@selector(platformAxElement)] ? [parent platformAxElement] : parent;
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

- (void)setAccessibilityParent:(id)accessibilityParent {
    [[self javaBase] setParent:accessibilityParent];
}

- (NSUInteger)accessibilityIndexOfChild:(id)child {
    return [[self javaBase] accessibilityIndexOfChild:child];
}

@end
