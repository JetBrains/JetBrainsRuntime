// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#include "jni.h"

#import <AppKit/AppKit.h>
#import <JavaNativeFoundation/JavaNativeFoundation.h>

//#define JAVA_AX_DEBUG 1
//#define JAVA_AX_NO_IGNORES 1
//#define JAVA_AX_DEBUG_PARMS 1

// these constants are duplicated in CAccessibility.java
#define JAVA_AX_ALL_CHILDREN (-1)
#define JAVA_AX_SELECTED_CHILDREN (-2)
#define JAVA_AX_VISIBLE_CHILDREN (-3)
// If the value is >=0, it's an index

extern JNFClassInfo sjc_CAccessible;
extern JNFMemberInfo *jm_getChildrenAndRoles;
extern JNFMemberInfo *jf_ptr;
extern JNFMemberInfo *sjm_getCAccessible;
extern JNFMemberInfo *sjm_getAccessibleIndexInParent;

@interface JavaElementAccessibility : NSAccessibilityElement
{
    NSView *fView;
    NSObject *fParent;

    NSString *fNSRole;
    NSString *fJavaRole;

    jint fIndex;
    jobject fAccessible;
    jobject fComponent;

    NSMutableDictionary *fActions;
    NSObject *fActionsLOCK;
}

- (id)initWithParent:(NSObject*)parent withEnv:(JNIEnv *)env withAccessible:(jobject)accessible withIndex:(jint)index withView:(NSView *)view withJavaRole:(NSString *)javaRole;
- (void)unregisterFromCocoaAXSystem;
- (void)postValueChanged;
- (void)postSelectedTextChanged;
- (void)postSelectionChanged;
- (BOOL)isEqual:(id)anObject;
- (BOOL)isAccessibleWithEnv:(JNIEnv *)env forAccessible:(jobject)accessible;

+ (void)postFocusChanged:(id)message;

+ (NSArray*)childrenOfParent:(JavaElementAccessibility*)parent withEnv:(JNIEnv *)env withChildrenCode:(NSInteger)whichChildren allowIgnored:(BOOL)allowIgnored;
+ (JavaElementAccessibility *) createWithParent:(JavaElementAccessibility *)parent accessible:(jobject)jaccessible role:(NSString *)javaRole index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view;
+ (JavaElementAccessibility *) createWithAccessible:(jobject)jaccessible role:(NSString *)role index:(jint)index withEnv:(JNIEnv *)env withView:(NSView *)view;
+ (JavaElementAccessibility *) createWithAccessible:(jobject)jaccessible withEnv:(JNIEnv *)env withView:(NSView *)view;

- (NSDictionary*)getActions:(JNIEnv *)env;
- (void)getActionsWithEnv:(JNIEnv *)env;

- (jobject)axContextWithEnv:(JNIEnv *)env;
- (NSView*)view;
- (NSWindow*)window;
- (id)parent;
- (NSString *)javaRole;
- (BOOL)isMenu;
- (BOOL)isSelected:(JNIEnv *)env;
- (BOOL)isSelectable:(JNIEnv *)env;
- (BOOL)isVisible:(JNIEnv *)env;

- (BOOL)accessibilityIsIgnored;
- (id)accessibilityHitTest:(NSPoint)point withEnv:(JNIEnv *)env;
- (id)accessibilityFocusedUIElement;

@end
