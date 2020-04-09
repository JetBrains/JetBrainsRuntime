// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaListAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"

@implementation JavaListAccessibility

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilityRows
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaBaseAccessibility childrenOfParent:self
                                                           withEnv:env
                                                  withChildrenCode:JAVA_AX_ALL_CHILDREN
                                                      allowIgnored:NO];
    NSArray *value = nil;
    if ([children count] > 0) {
        value = children;
    }
    return value;
}

- (BOOL)isAccessibilityElement
{
    return YES;
}

- (NSString *)accessibilityLabel
{
    return @"List";
}

- (NSArray *)accessibilityChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *selectedChildren = [JavaBaseAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_ALL_CHILDREN allowIgnored:NO];
    if ([selectedChildren count] > 0) {
        return selectedChildren;
    }

    return nil;
}

- (NSArray *)accessibilitySelectedChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *selectedChildren = [JavaBaseAccessibility childrenOfParent:self withEnv:env withChildrenCode:JAVA_AX_SELECTED_CHILDREN allowIgnored:NO];
    if ([selectedChildren count] > 0) {
        return selectedChildren;
    }

    return nil;
}

- (NSRect)accessibilityFrame
{
    return NSMakeRect(0, 0, 100, 100);
}

- (id)accessibilityParent
{
   return [self parent];
}

// -- misc accessibility --
- (BOOL)accessibilityIsIgnored
{
#ifdef JAVA_AX_NO_IGNORES
    return NO;
#else
//    return [[self accessibilityRoleAttribute] isEqualToString:JavaAccessibilityIgnore];
    return NO;
#endif /* JAVA_AX_NO_IGNORES */
}

- (BOOL)isAccessibilityEnabled
{
    return YES;
}

- (id)accessibilityHitTest:(NSPoint)point withEnv:(JNIEnv *)env
{
    static JNF_CLASS_CACHE(jc_Container, "java/awt/Container");
    static JNF_STATIC_MEMBER_CACHE(jm_accessibilityHitTest, sjc_CAccessibility, "accessibilityHitTest", "(Ljava/awt/Container;FF)Ljavax/accessibility/Accessible;");

    // Make it into java screen coords
    point.y = [[[[self view] window] screen] frame].size.height - point.y;

    jobject jparent = fComponent;

    id value = nil;
    if (JNFIsInstanceOf(env, jparent, &jc_Container)) {
        jobject jaccessible = JNFCallStaticObjectMethod(env, jm_accessibilityHitTest, jparent, (jfloat) point.x, (jfloat) point.y); // AWT_THREADING Safe (AWTRunLoop)
        if (jaccessible != NULL) {
            value = [JavaBaseAccessibility createWithAccessible:jaccessible withEnv:env withView:fView];
            (*env)->DeleteLocalRef(env, jaccessible);
        }
    }

    if (value == nil) {
        value = self;
    }

    if ([value accessibilityIsIgnored]) {
        value = NSAccessibilityUnignoredAncestor(value);
    }

#ifdef JAVA_AX_DEBUG
    NSLog(@"%s: %@", __FUNCTION__, value);
#endif
    return value;
}

- (id)accessibilityFocusedUIElement
{
    static JNF_STATIC_MEMBER_CACHE(jm_getFocusOwner, sjc_CAccessibility, "getFocusOwner", "(Ljava/awt/Component;)Ljavax/accessibility/Accessible;");

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    id value = nil;

    NSWindow *hostWindow = [[self->fView window] retain];
    jobject focused = JNFCallStaticObjectMethod(env, jm_getFocusOwner, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    [hostWindow release];

    if (focused != NULL) {
        if (JNFIsInstanceOf(env, focused, &sjc_Accessible)) {
            value = [JavaBaseAccessibility createWithAccessible:focused withEnv:env withView:fView];
        }
        (*env)->DeleteLocalRef(env, focused);
    }

    if (value == nil) {
        value = self;
    }
#ifdef JAVA_AX_DEBUG
    NSLog(@"%s: %@", __FUNCTION__, value);
#endif
    return value;
}

@end

//@implementation JavaRowAccessibility
//@end
