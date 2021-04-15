// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComboBoxAccessibility.h"
#import "JavaAccessibilityAction.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>


@implementation JavaComboBoxAccessibility

// NSAccessibilityElement protocol methods

- (id)accessibilityValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return nil;
    static jclass cls = (*env)->GetObjectClass(env, axContext);
    DECLARE_METHOD_RETURN(jm_getAccessibleSelection, cls, "getAccessibleSelection", "(I)Ljavax/accessibility/Accessible;", nil);
    jobject axSelectedChild = (*env)->CallObjectMethod(env, axContext, jm_getAccessibleSelection, 0);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axContext);
    if (axSelectedChild == NULL) {
        return nil;
    }
    DECLARE_CLASS_RETURN(sjc_CAccessible, "sun/lwawt/macosx/CAccessible", nil);
    DECLARE_STATIC_METHOD_RETURN(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName",
                          "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", nil);
    jobject childName = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getAccessibleName, axSelectedChild, fComponent);
    CHECK_EXCEPTION();
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
