// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaElementAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"

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
    RaiseMustOverrideException(@"accessibilityLabel");
    return NULL;
}

- (NSArray *)accessibilityChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [JavaBaseAccessibility childrenOfParent:self.javaBase
                                                        withEnv:env
                                               withChildrenCode:JAVA_AX_ALL_CHILDREN
                                                   allowIgnored:NO];
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
                                                           allowIgnored:NO];
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
    return YES;
}

- (BOOL)isAccessibilityEnabled
{
    RaiseMustOverrideException(@"isAccessibilityEnabled");
    return NO;
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
