// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaListAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"

@implementation JavaListAccessibility

- (NSString *)getPlatformAxObjectClassName
{
    return @"PlatformAxList";
}

@end

@implementation PlatformAxList

@synthesize javaAxObject;

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilityRows
{
    return [self accessibilityChildren];
}

- (nullable NSArray<id<NSAccessibilityRow>> *)accessibilitySelectedRows
{
    return [self accessibilitySelectedChildren];
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
    NSArray *children = [JavaBaseAccessibility childrenOfParent:self.javaAxObject
                                                        withEnv:env
                                               withChildrenCode:JAVA_AX_ALL_CHILDREN
                                                   allowIgnored:NO];
    if ([children count] > 0) {
        return children;
    }
    return nil;
}

- (NSArray *)accessibilitySelectedChildren
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *selectedChildren = [JavaBaseAccessibility childrenOfParent:self.javaAxObject
                                                                withEnv:env
                                                       withChildrenCode:JAVA_AX_SELECTED_CHILDREN
                                                           allowIgnored:NO];
    if ([selectedChildren count] > 0) {
        return selectedChildren;
    }
    return nil;
}

- (NSRect)accessibilityFrame
{
    return [self.javaAxObject getBounds];
}

- (id)accessibilityParent
{
    JavaBaseAccessibility *parent = (JavaBaseAccessibility *) [self.javaAxObject parent];
    return parent.platformAxObject;
}

- (BOOL)accessibilityIsIgnored
{
    return NO;
}

- (BOOL)isAccessibilityEnabled
{
    return YES;
}

- (id)accessibilityApplicationFocusedUIElement
{
    return [self getFocusedElement];
}

@end

//@implementation JavaRowAccessibility
//@end
