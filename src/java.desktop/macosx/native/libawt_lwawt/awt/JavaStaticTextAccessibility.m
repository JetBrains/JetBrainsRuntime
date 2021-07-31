// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaStaticTextAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static jclass sjc_CAccessibility = NULL;
#define GET_CACCESSIBLITY_CLASS() \
     GET_CLASS(sjc_CAccessibility, "sun/lwawt/macosx/CAccessibility");
#define GET_CACCESSIBLITY_CLASS_RETURN(ret) \
     GET_CLASS_RETURN(sjc_CAccessibility, "sun/lwawt/macosx/CAccessibility", ret);

static jclass sjc_CAccessibleText = NULL;
#define GET_CACCESSIBLETEXT_CLASS() \
    GET_CLASS(sjc_CAccessibleText, "sun/lwawt/macosx/CAccessibleText");
#define GET_CACCESSIBLETEXT_CLASS_RETURN(ret) \
    GET_CLASS_RETURN(sjc_CAccessibleText, "sun/lwawt/macosx/CAccessibleText", ret);


@implementation JavaStaticTextAccessibility

// NSAccessibilityElement protocol methods

- (id)accessibilityValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLITY_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName",
                          "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", nil);
    jobject axName = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                       sjm_getAccessibleName, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axName != NULL) {
        NSString* str = JavaStringToNSString(env, axName);
        (*env)->DeleteLocalRef(env, axName);
        return str;
    }
    return @"";
}

- (NSRect)accessibilityFrame {
    return [super accessibilityFrame];
}

- (id)accessibilityParent {
    return [super accessibilityParent];
}

- (NSRange)accessibilityVisibleCharacterRange {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(NSRangeFromString(@""));
    DECLARE_STATIC_METHOD_RETURN(jm_getVisibleCharacterRange, sjc_CAccessibleText, "getVisibleCharacterRange",
                          "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)[I", NSRangeFromString(@""));
    jintArray axTextRange = (*env)->CallStaticObjectMethod(env, sjc_CAccessibleText,
                 jm_getVisibleCharacterRange, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axTextRange == NULL) return NSRangeFromString(@"");

    return [javaConvertIntArrayToNSRangeValue(env, axTextRange) rangeValue];
}

@end

NSValue *javaConvertIntArrayToNSRangeValue(JNIEnv* env, jintArray array)  {
    jint *values = (*env)->GetIntArrayElements(env, array, 0);
    if (values == NULL) {
        // Note: Java will not be on the stack here so a java exception can't happen and no need to call ExceptionCheck.
        NSLog(@"%s failed calling GetIntArrayElements", __FUNCTION__);
        return nil;
    };
    NSValue *value = [NSValue valueWithRange:NSMakeRange(values[0], values[1] - values[0])];
    (*env)->ReleaseIntArrayElements(env, array, values, 0);
    return value;
}
