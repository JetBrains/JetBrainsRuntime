// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComboBoxAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");

static const char* ACCESSIBLE_JCOMBOBOX_NAME = "javax.swing.JComboBox$AccessibleJComboBox";

@implementation JavaComboBoxAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxComboBox";
}

- (NSString *)accessibleSelectedText {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JCOMBOBOX_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, [self axContextWithEnv:env]);
    JNF_MEMBER_CACHE(jm_getAccessibleSelection, clsInfo, "getAccessibleSelection", "(I)Ljavax/accessibility/Accessible;");
    jobject axSelectedChild = JNFCallObjectMethod(env, [self axContextWithEnv:env], jm_getAccessibleSelection, 0);
    if (axSelectedChild == NULL) {
        return nil;
    }
    jobject childName = JNFCallStaticObjectMethod(env, sjm_getAccessibleName, axSelectedChild, fComponent);
    if (childName == NULL) {
        (*env)->DeleteLocalRef(env, axSelectedChild);
        return nil;
    }
    NSString *selectedText = JNFObjectToString(env, childName);
    (*env)->DeleteLocalRef(env, axSelectedChild);
    (*env)->DeleteLocalRef(env, childName);
    return selectedText;
}

- (void)accessibleShowMenu {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getAccessibleAction, sjc_CAccessibility, "getAccessibleAction", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleAction;");

    // On MacOSX, text doesn't have actions, in java it does.
    // cmcnote: NOT TRUE - Editable text has AXShowMenu. Textfields have AXConfirm. Static text has no actions.
    jobject axAction = JNFCallStaticObjectMethod(env, jm_getAccessibleAction, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axAction != NULL) {
        //+++gdb NOTE: In MacOSX, there is just a single Action, not multiple. In java,
        //  the first one seems to be the most basic, so this will be used.
        // cmcnote: NOT TRUE - Sometimes there are multiple actions, eg sliders have AXDecrement AND AXIncrement (radr://3893192)
        JavaAxAction *action = [[JavaAxAction alloc] initWithEnv:env withAccessibleAction:axAction withIndex:0 withComponent:fComponent];
        [action perform];
        [action release];
        (*env)->DeleteLocalRef(env, axAction);
    }
}

@end

@implementation PlatformAxComboBox

- (id)accessibilityValue {
    return [(JavaComboBoxAccessibility *)[self javaBase] accessibleSelectedText];
}

- (BOOL)accessibilityPerformPress {
    [(JavaComboBoxAccessibility *)[self javaBase] accessibleShowMenu];
    return YES;
}

- (BOOL)isAccessibilityEnabled {
    return YES;
}

@end
