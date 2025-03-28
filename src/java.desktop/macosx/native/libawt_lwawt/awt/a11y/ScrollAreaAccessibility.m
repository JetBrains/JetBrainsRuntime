/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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

#import "ScrollAreaAccessibility.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import "sun_lwawt_macosx_CAccessibility.h"

static jclass sjc_CAccessibility = NULL;
static jmethodID sjm_getScrollBar = NULL;

/*
 * Implementation of the accessibility peer for the ScrollArea role
 */
@implementation ScrollAreaAccessibility

- (NSArray * _Nullable)accessibilityContentsAttribute
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    NSArray *children = [CommonComponentAccessibility childrenOfParent:self withEnv:env withChildrenCode:sun_lwawt_macosx_CAccessibility_JAVA_AX_ALL_CHILDREN allowIgnored:YES];

    if ([children count] <= 0) return nil;
    NSArray *contents = [NSMutableArray arrayWithCapacity:[children count]];

    // The scroll bars are in the children. children less the scroll bars is the contents
    NSEnumerator *enumerator = [children objectEnumerator];
    CommonComponentAccessibility *aElement;
    while ((aElement = (CommonComponentAccessibility *)[enumerator nextObject])) {
        if (![[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
            // no scroll bars in contents
            [(NSMutableArray *)contents addObject:aElement];
        }
    }
    return contents;
}

- (id _Nullable)getScrollBarwithOrientation:(enum NSAccessibilityOrientation)orientation
{
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    // Firstly, try to get the scroll bar using getHorizontalScrollBar/getVerticalScrollBar methods of JScrollPane.
    jobject scrollBar = NULL;
    GET_CACCESSIBILITY_CLASS_RETURN(nil);
    GET_STATIC_METHOD_RETURN(sjm_getScrollBar, sjc_CAccessibility, "getScrollBar",
                             "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)Ljavax/accessibility/Accessible;", nil);
    scrollBar = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getScrollBar, fAccessible, fComponent, orientation);
    CHECK_EXCEPTION();

    if (scrollBar != NULL) {
        CommonComponentAccessibility *axScrollBar = nil;
        DECLARE_CLASS_RETURN(sjc_Accessible, "javax/accessibility/Accessible", nil);
        if ((*env)->IsInstanceOf(env, scrollBar, sjc_Accessible)) {
            axScrollBar = [CommonComponentAccessibility createWithAccessible:scrollBar withEnv:env withView:fView];
        }
        (*env)->DeleteLocalRef(env, scrollBar);
        if (axScrollBar != nil) {
            return axScrollBar;
        }
    }

    // Otherwise, try to search for the scroll bar within the children.
    NSArray *children = [CommonComponentAccessibility childrenOfParent:self withEnv:env withChildrenCode:sun_lwawt_macosx_CAccessibility_JAVA_AX_ALL_CHILDREN allowIgnored:YES];
    if ([children count] <= 0) return nil;

    CommonComponentAccessibility *aElement;
    NSEnumerator *enumerator = [children objectEnumerator];
    while ((aElement = (CommonComponentAccessibility *)[enumerator nextObject])) {
        if ([[aElement accessibilityRole] isEqualToString:NSAccessibilityScrollBarRole]) {
            jobject elementAxContext = [aElement axContextWithEnv:env];
            if (orientation == NSAccessibilityOrientationHorizontal) {
                if (isHorizontal(env, elementAxContext, fComponent)) {
                    (*env)->DeleteLocalRef(env, elementAxContext);
                    return aElement;
                }
            } else if (orientation == NSAccessibilityOrientationVertical) {
                if (isVertical(env, elementAxContext, fComponent)) {
                    (*env)->DeleteLocalRef(env, elementAxContext);
                    return aElement;
                }
            } else {
                (*env)->DeleteLocalRef(env, elementAxContext);
            }
        }
    }
    return nil;
}

- (NSAccessibilityRole _Nonnull)accessibilityRole
{
    return NSAccessibilityScrollAreaRole;
}

- (NSArray * _Nullable)accessibilityContents
{
    return [self accessibilityContentsAttribute];
}

- (id _Nullable)accessibilityHorizontalScrollBar
{
    return [self getScrollBarwithOrientation:NSAccessibilityOrientationHorizontal];
}

- (id _Nullable)accessibilityVerticalScrollBar
{
    return [self getScrollBarwithOrientation:NSAccessibilityOrientationVertical];
}
@end
