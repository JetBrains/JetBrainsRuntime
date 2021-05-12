// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaTabGroupAccessibility.h"
#import "JavaTabButtonAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static JNF_STATIC_MEMBER_CACHE(jm_getChildrenAndRoles, sjc_CAccessibility, "getChildrenAndRoles", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;IZ)[Ljava/lang/Object;");

@implementation JavaTabGroupAccessibility

- (id)currentTabWithEnv:(JNIEnv *)env withAxContext:(jobject)axContext {
    NSArray *tabs = [self tabButtonsWithEnv:env withTabGroupAxContext:axContext withTabCode:JAVA_AX_ALL_CHILDREN allowIgnored:NO];

    // Looking at the JTabbedPane sources, there is always one AccessibleSelection.
    jobject selAccessible = getAxContextSelection(env, axContext, 0, fComponent);
    if (selAccessible == NULL) return nil;

    // Go through the tabs and find selAccessible
    _numTabs = [tabs count];
    JavaComponentAccessibility *aTab;
    NSInteger i;
    for (i = 0; i < _numTabs; i++) {
        aTab = [tabs objectAtIndex:i];
        if ([aTab isAccessibleWithEnv:env forAccessible:selAccessible]) {
            (*env)->DeleteLocalRef(env, selAccessible);
            return aTab;
        }
    }
    (*env)->DeleteLocalRef(env, selAccessible);
    return nil;
}

- (NSArray *)tabButtonsWithEnv:(JNIEnv *)env withTabGroupAxContext:(jobject)axContext withTabCode:(NSInteger)whichTabs allowIgnored:(BOOL)allowIgnored {
    jobjectArray jtabsAndRoles = (jobjectArray)JNFCallStaticObjectMethod(env, jm_getChildrenAndRoles, fAccessible, fComponent, whichTabs, allowIgnored); // AWT_THREADING Safe (AWTRunLoop)
    if(jtabsAndRoles == NULL) return nil;

    jsize arrayLen = (*env)->GetArrayLength(env, jtabsAndRoles);
    if (arrayLen == 0) {
        (*env)->DeleteLocalRef(env, jtabsAndRoles);
        return nil;
    }
    NSMutableArray *tabs = [NSMutableArray arrayWithCapacity:(arrayLen/2)];

    // all of the tabs have the same role, so we can just find out what that is here and use it for all the tabs
    jobject jtabJavaRole = (*env)->GetObjectArrayElement(env, jtabsAndRoles, 1); // the array entries alternate between tab/role, starting with tab. so the first role is entry 1.
    if (jtabJavaRole == NULL) {
        (*env)->DeleteLocalRef(env, jtabsAndRoles);
        return nil;
    }
    jobject jkey = JNFGetObjectField(env, jtabJavaRole, sjf_key);
    NSString *tabJavaRole = JNFJavaToNSString(env, jkey);
    (*env)->DeleteLocalRef(env, jkey);

    NSInteger i;
    NSUInteger tabIndex = (whichTabs >= 0) ? whichTabs : 0; // if we're getting one particular child, make sure to set its index correctly
    for(i = 0; i < arrayLen; i+=2) {
        jobject jtab = (*env)->GetObjectArrayElement(env, jtabsAndRoles, i);
        JavaComponentAccessibility *tab = [[[JavaTabButtonAccessibility alloc] initWithParent:self withEnv:env withAccessible:jtab withIndex:tabIndex withTabGroup:axContext withView:[self view] withJavaRole:tabJavaRole] autorelease];
        (*env)->DeleteLocalRef(env, jtab);
        [tabs addObject:tab];
        tabIndex++;
    }
    (*env)->DeleteLocalRef(env, jtabsAndRoles);
    return tabs;
}

- (NSArray *)contentsWithEnv:(JNIEnv *)env withTabGroupAxContext:(jobject)axContext withTabCode:(NSInteger)whichTabs allowIgnored:(BOOL)allowIgnored {
    // Contents are the children of the selected tab.
    JavaComponentAccessibility *currentTab = [self currentTabWithEnv:env withAxContext:axContext];
    if (currentTab == nil) return nil;

    NSArray *contents = [JavaComponentAccessibility childrenOfParent:currentTab withEnv:env withChildrenCode:whichTabs allowIgnored:allowIgnored];
    if ([contents count] <= 0) return nil;
    return contents;
}

- (NSInteger)numTabs {
    return _numTabs;
}

// NSAccessibilityElement protocol methods

- (NSArray *)accessibilityTabs {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    id tabs = [self tabButtonsWithEnv:env withTabGroupAxContext:axContext withTabCode:JAVA_AX_ALL_CHILDREN allowIgnored:NO];
    (*env)->DeleteLocalRef(env, axContext);
    return tabs;
}

- (NSArray *)accessibilityContents {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    NSArray* cont = [self contentsWithEnv:env withTabGroupAxContext:axContext withTabCode:JAVA_AX_ALL_CHILDREN allowIgnored:NO];
    (*env)->DeleteLocalRef(env, axContext);
    return cont;
}

- (id)accessibilityValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    id val = [self currentTabWithEnv:env withAxContext:axContext];
    (*env)->DeleteLocalRef(env, axContext);
    return val;
}

- (NSArray *)accessibilityChildren {
    //children = AXTabs + AXContents
    NSArray *tabs = [self accessibilityTabs];
    NSArray *contents = [self accessibilityContents];

    NSMutableArray *children = [NSMutableArray arrayWithCapacity:[tabs count] + [contents count]];
    [children addObjectsFromArray:tabs];
    [children addObjectsFromArray:contents];

    return (NSArray *)children;
}

- (NSArray *)accessibilityArrayAttributeValues:(NSAccessibilityAttributeName)attribute index:(NSUInteger)index maxCount:(NSUInteger)maxCount {
    NSArray *result = nil;
    if ( (maxCount == 1) && [attribute isEqualToString:NSAccessibilityChildrenAttribute]) {
        // Children codes for ALL, SELECTED, VISIBLE are <0. If the code is >=0, we treat it as an index to a single child
        JNIEnv *env = [ThreadUtilities getJNIEnv];
        jobject axContext = [self axContextWithEnv:env];

        //children = AXTabs + AXContents
        NSArray *children = [self tabButtonsWithEnv:env
                                                                            withTabGroupAxContext:axContext
                                                                                      withTabCode:index
                                                                                     allowIgnored:NO]; // first look at the tabs
        if ([children count] > 0) {
            result = children;
         } else {
            children= [self contentsWithEnv:env
                                                                    withTabGroupAxContext:axContext
                                                                              withTabCode:(index-[self numTabs])
                                                                             allowIgnored:NO];
            if ([children count] > 0) {
                result = children;
            }
        }
        (*env)->DeleteLocalRef(env, axContext);
    } else {
        result = [super accessibilityArrayAttributeValues:attribute index:index maxCount:maxCount];
    }
    return result;
}

- (void)setAccessibilityValue:(id)accessibilityValue {
    NSNumber *number = (NSNumber *)accessibilityValue;
    if (![number boolValue]) return;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    setAxContextSelection(env, axContext, self->fIndex, self->fComponent);
    (*env)->DeleteLocalRef(env, axContext);
}

@end
