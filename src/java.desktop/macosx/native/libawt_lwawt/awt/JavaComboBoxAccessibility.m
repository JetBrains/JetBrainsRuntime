// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComboBoxAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");

static const char* ACCESSIBLE_JCOMBOBOX_NAME = "javax.swing.JComboBox$AccessibleJComboBox";

@implementation JavaComboBoxAccessibility

// NSAccessibilityElement protocol methods

- (id)accessibilityValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return nil;
    JNFClassInfo clsInfo;
    clsInfo.name = ACCESSIBLE_JCOMBOBOX_NAME;
    clsInfo.cls = (*env)->GetObjectClass(env, axContext);
    JNF_MEMBER_CACHE(jm_getAccessibleSelection, clsInfo, "getAccessibleSelection", "(I)Ljavax/accessibility/Accessible;");
    jobject axSelectedChild = JNFCallObjectMethod(env, axContext, jm_getAccessibleSelection, 0);
    (*env)->DeleteLocalRef(env, axContext);
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

@end
