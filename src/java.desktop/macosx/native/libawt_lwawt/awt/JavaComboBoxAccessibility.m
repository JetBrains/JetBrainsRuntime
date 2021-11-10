// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaComboBoxAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"

static jclass sjc_CAccessibility = NULL;

static jmethodID sjm_getAccessibleName = NULL;
#define GET_ACCESSIBLENAME_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", \
                     "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", ret);

static jmethodID sjm_getAccessibleSelection = NULL;
#define GET_ACCESSIBLESELECTION_METHOD_RETURN(ret) \
    GET_CACCESSIBILITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(sjm_getAccessibleSelection, sjc_CAccessibility, "getAccessibleSelection", \
                     "(Ljavax/accessibility/AccessibleContext;Ljava/awt/Component;)Ljavax/accessibility/AccessibleSelection;", ret);

@implementation JavaComboBoxAccessibility

- (JavaComponentAccessibility *)accessibleSelection {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axContext = [self axContextWithEnv:env];
    if (axContext == NULL) return nil;
    GET_ACCESSIBLESELECTION_METHOD_RETURN(nil);
    jobject axSelection = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility, sjm_getAccessibleSelection, axContext, self->fComponent);
    CHECK_EXCEPTION();
    if (axSelection == NULL) {
        return nil;
    }
    jclass axSelectionClass = (*env)->GetObjectClass(env, axSelection);
    DECLARE_METHOD_RETURN(jm_getAccessibleSelection, axSelectionClass, "getAccessibleSelection", "(I)Ljavax/accessibility/Accessible;", nil);
    jobject axSelectedChild = (*env)->CallObjectMethod(env, axSelection, jm_getAccessibleSelection, 0);
    CHECK_EXCEPTION();
    (*env)->DeleteLocalRef(env, axSelection);
    (*env)->DeleteLocalRef(env, axContext);
    if (axSelectedChild == NULL) {
        return nil;
    }
    return [JavaComponentAccessibility createWithAccessible:axSelectedChild withEnv:env withView:fView];
}

// NSAccessibilityElement protocol methods

- (id)accessibilityValue {
    return [[self accessibleSelection] accessibilityLabel];
}

- (NSArray *)accessibilitySelectedChildren {
    return [NSArray arrayWithObject:[self accessibleSelection]];
}

@end
