// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaStaticTextAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static JNF_CLASS_CACHE(sjc_CAccessibleText, "sun/lwawt/macosx/CAccessibleText");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleText, sjc_CAccessibility, "getAccessibleText", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleText;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleEditableText, sjc_CAccessibleText, "getAccessibleEditableText", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleEditableText;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");

@implementation JavaStaticTextAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxStaticText";
}

- (NSString *)accessibleValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

        jobject axName = JNFCallStaticObjectMethod(env, sjm_getAccessibleName, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
        if (axName != NULL) {
            NSString* str = JNFJavaToNSString(env, axName);
            (*env)->DeleteLocalRef(env, axName);
            return str;
        }
        return @"";
}

- (NSValue *)accessibleVisibleCharacterRange {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getVisibleCharacterRange, sjc_CAccessibleText, "getVisibleCharacterRange", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)[I");
    jintArray axTextRange = JNFCallStaticObjectMethod(env, jm_getVisibleCharacterRange, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axTextRange == NULL) return nil;

    return javaConvertIntArrayToNSRangeValue(env, axTextRange);
}

@end

@implementation PlatformAxStaticText

- (id)accessibilityValue {
    return [(JavaStaticTextAccessibility *)[self javaBase] accessibleValue];
}

- (NSRect)accessibilityFrame {
    return [super accessibilityFrame];
}

- (id)accessibilityParent {
    return [super accessibilityParent];
}

- (NSRange)accessibilityVisibleCharacterRange {
    return [[(JavaStaticTextAccessibility *)[self javaBase] accessibleVisibleCharacterRange] rangeValue];
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
