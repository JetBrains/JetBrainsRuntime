/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "jni.h"

#import <AppKit/AppKit.h>
#import "JavaBaseAccessibility.h"

@interface JavaComponentAccessibility : JavaBaseAccessibility {
    NSMutableDictionary *fActions;
    NSObject *fActionsLOCK;
}

- (NSDictionary*)getActions:(JNIEnv *)env;
- (void)getActionsWithEnv:(JNIEnv *)env;

// attribute names
- (NSArray *)initializeAttributeNamesWithEnv:(JNIEnv *)env;
- (NSArray *)accessibilityAttributeNames;

// attributes
- (id)accessibilityAttributeValue:(NSString *)attribute;
- (BOOL)accessibilityIsAttributeSettable:(NSString *)attribute;
- (void)accessibilitySetValue:(id)value forAttribute:(NSString *)attribute;

- (NSArray *)accessibilityChildrenAttribute;
- (BOOL)accessibilityIsChildrenAttributeSettable;
- (NSUInteger)accessibilityIndexOfChild:(id)child;
- (NSArray *)accessibilityArrayAttributeValues:(NSString *)attribute
    index:(NSUInteger)index maxCount:(NSUInteger)maxCount;
- (NSNumber *)accessibilityEnabledAttribute;
- (BOOL)accessibilityIsEnabledAttributeSettable;
- (NSNumber *)accessibilityFocusedAttribute;
- (BOOL)accessibilityIsFocusedAttributeSettable;
- (void)accessibilitySetFocusedAttribute:(id)value;
- (NSString *)accessibilityHelpAttribute;
- (BOOL)accessibilityIsHelpAttributeSettable;
- (NSValue *)accessibilityIndexAttribute;
- (BOOL)accessibilityIsIndexAttributeSettable;
- (id)accessibilityMaxValueAttribute;
- (BOOL)accessibilityIsMaxValueAttributeSettable;
- (id)accessibilityMinValueAttribute;
- (BOOL)accessibilityIsMinValueAttributeSettable;
- (id)accessibilityOrientationAttribute;
- (BOOL)accessibilityIsOrientationAttributeSettable;
- (id)accessibilityParentAttribute;
- (BOOL)accessibilityIsParentAttributeSettable;
- (NSValue *)accessibilityPositionAttribute;
- (BOOL)accessibilityIsPositionAttributeSettable;
- (NSString *)accessibilityRoleAttribute;
- (BOOL)accessibilityIsRoleAttributeSettable;
- (NSString *)accessibilityRoleDescriptionAttribute;
- (BOOL)accessibilityIsRoleDescriptionAttributeSettable;
- (NSArray *)accessibilitySelectedChildrenAttribute;
- (BOOL)accessibilityIsSelectedChildrenAttributeSettable;
- (NSNumber *)accessibilitySelectedAttribute;
- (BOOL)accessibilityIsSelectedAttributeSettable;
- (void)accessibilitySetSelectedAttribute:(id)value;
- (NSValue *)accessibilitySizeAttribute;
- (BOOL)accessibilityIsSizeAttributeSettable;
- (NSString *)accessibilitySubroleAttribute;
- (BOOL)accessibilityIsSubroleAttributeSettable;
- (NSString *)accessibilityTitleAttribute;
- (BOOL)accessibilityIsTitleAttributeSettable;
- (NSWindow *)accessibilityTopLevelUIElementAttribute;
- (BOOL)accessibilityIsTopLevelUIElementAttributeSettable;
- (id)accessibilityValueAttribute;
- (BOOL)accessibilityIsValueAttributeSettable;
- (void)accessibilitySetValueAttribute:(id)value;
- (NSArray *)accessibilityVisibleChildrenAttribute;
- (BOOL)accessibilityIsVisibleChildrenAttributeSettable;
- (id)accessibilityWindowAttribute;
- (BOOL)accessibilityIsWindowAttributeSettable;

// actions
- (NSArray *)accessibilityActionNames;
- (NSString *)accessibilityActionDescription:(NSString *)action;
- (void)accessibilityPerformAction:(NSString *)action;

- (BOOL)accessibilityIsIgnored;
- (id)accessibilityHitTest:(NSPoint)point withEnv:(JNIEnv *)env;
- (id)accessibilityFocusedUIElement;

@end

@interface TabGroupAccessibility : JavaComponentAccessibility {
    NSInteger _numTabs;
}

- (id)currentTabWithEnv:(JNIEnv *)env withAxContext:(jobject)axContext;
- (NSArray *)tabControlsWithEnv:(JNIEnv *)env withTabGroupAxContext:(jobject)axContext withTabCode:(NSInteger)whichTabs allowIgnored:(BOOL)allowIgnored;
- (NSArray *)contentsWithEnv:(JNIEnv *)env withTabGroupAxContext:(jobject)axContext withTabCode:(NSInteger)whichTabs allowIgnored:(BOOL)allowIgnored;
- (NSArray *)initializeAttributeNamesWithEnv:(JNIEnv *)env;

- (NSArray *)accessibilityArrayAttributeValues:(NSString *)attribute index:(NSUInteger)index maxCount:(NSUInteger)maxCount;
- (NSArray *)accessibilityChildrenAttribute;
- (id) accessibilityTabsAttribute;
- (BOOL)accessibilityIsTabsAttributeSettable;
- (NSArray *)accessibilityContentsAttribute;
- (BOOL)accessibilityIsContentsAttributeSettable;
- (id) accessibilityValueAttribute;

@end

@interface TabGroupControlAccessibility : JavaComponentAccessibility {
    jobject fTabGroupAxContext;
}
- (id)initWithParent:(NSObject *)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withTabGroup:(jobject)tabGroup withView:(NSView *)view withJavaRole:(NSString *)javaRole;
- (jobject)tabGroup;
- (void)getActionsWithEnv:(JNIEnv *)env;

- (id)accessibilityValueAttribute;
@end
