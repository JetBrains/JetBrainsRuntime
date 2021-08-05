// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaNavigableTextAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import "JNIUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static jclass sjc_CAccessibility = NULL;
#define GET_CACCESSIBLITY_CLASS() \
     GET_CLASS(sjc_CAccessibility, "sun/lwawt/macosx/CAccessibility");
#define GET_CACCESSIBLITY_CLASS_RETURN(ret) \
     GET_CLASS_RETURN(sjc_CAccessibility, "sun/lwawt/macosx/CAccessibility", ret);

static jmethodID sjm_getAccessibleText = NULL;
#define GET_ACCESSIBLETEXT_METHOD_RETURN(ret) \
    GET_CACCESSIBLITY_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(sjm_getAccessibleText, sjc_CAccessibility, "getAccessibleText", \
              "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleText;", ret);

static jclass sjc_CAccessibleText = NULL;
#define GET_CACCESSIBLETEXT_CLASS() \
    GET_CLASS(sjc_CAccessibleText, "sun/lwawt/macosx/CAccessibleText");
#define GET_CACCESSIBLETEXT_CLASS_RETURN(ret) \
    GET_CLASS_RETURN(sjc_CAccessibleText, "sun/lwawt/macosx/CAccessibleText", ret);

static jmethodID sjm_getAccessibleEditableText = NULL;
#define GET_ACCESSIBLEEDITABLETEXT_METHOD_RETURN(ret) \
    GET_CACCESSIBLETEXT_CLASS_RETURN(ret); \
    GET_STATIC_METHOD_RETURN(sjm_getAccessibleEditableText, sjc_CAccessibleText, "getAccessibleEditableText", \
              "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleEditableText;", ret);

@implementation JavaNavigableTextAccessibility

- (BOOL)accessibleIsValueSettable {
    // if text is enabled and editable, it's settable (according to NSCellTextAttributesAccessibility)
    BOOL isEnabled = [(NSNumber *)[self accessibilityEnabledAttribute] boolValue];
    if (!isEnabled) return NO;

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLEEDITABLETEXT_METHOD_RETURN(NO);
    jobject axEditableText = (*env)->CallStaticObjectMethod(env, sjc_CAccessibleText,
                     sjm_getAccessibleEditableText, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axEditableText == NULL) return NO;
    (*env)->DeleteLocalRef(env, axEditableText);
    return YES;
}

- (BOOL)accessibleIsPasswordText {
    return [[self javaRole] isEqualToString:@"passwordtext"];
}

// NSAccessibilityElement protocol methods

- (NSRect)accessibilityFrameForRange:(NSRange)range {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(NSMakeRect(0, 0, 0, 0));
    DECLARE_STATIC_METHOD_RETURN(jm_getBoundsForRange, sjc_CAccessibleText, "getBoundsForRange",
                         "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)[D", NSMakeRect(0, 0, 0, 0));
    jdoubleArray axBounds = (jdoubleArray)(*env)->CallStaticObjectMethod(env, sjc_CAccessibleText, jm_getBoundsForRange,
                              fAccessible, fComponent, range.location, range.length);
    CHECK_EXCEPTION();
    if (axBounds == NULL) return NSMakeRect(0, 0, 0, 0);

    // We cheat because we know that the array is 4 elements long (x, y, width, height)
    jdouble *values = (*env)->GetDoubleArrayElements(env, axBounds, 0);
    CHECK_EXCEPTION();
    if (values == NULL) {
        // Note: Java will not be on the stack here so a java exception can't happen and no need to call ExceptionCheck.
        NSLog(@"%s failed calling GetDoubleArrayElements", __FUNCTION__);
        return NSMakeRect(0, 0, 0, 0);
    };
    NSRect bounds;
    bounds.origin.x = values[0];
    bounds.origin.y = [[[[self view] window] screen] frame].size.height - values[1] - values[3]; //values[1] is y-coord from top-left of screen. Flip. Account for the height (values[3]) when flipping
    bounds.size.width = values[2];
    bounds.size.height = values[3];
    (*env)->ReleaseDoubleArrayElements(env, axBounds, values, 0);
    return bounds;
}

- (NSInteger)accessibilityLineForIndex:(NSInteger)index {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getLineNumberForIndex, sjc_CAccessibleText, "getLineNumberForIndex",
                           "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)I", nil);
    jint row = (*env)->CallStaticIntMethod(env, sjc_CAccessibleText, jm_getLineNumberForIndex,
                       fAccessible, fComponent, index);
    CHECK_EXCEPTION();
    if (row < 0) return nil;
    return row;
}

- (NSRange)accessibilityRangeForLine:(NSInteger)line {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(NSRangeFromString(@""));
    DECLARE_STATIC_METHOD_RETURN(jm_getRangeForLine, sjc_CAccessibleText, "getRangeForLine",
                 "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)[I", NSRangeFromString(@""));
    jintArray axTextRange = (jintArray)(*env)->CallStaticObjectMethod(env, sjc_CAccessibleText,
                jm_getRangeForLine, fAccessible, fComponent, line);
    CHECK_EXCEPTION();
    if (axTextRange == NULL) return NSRangeFromString(@"");

    return [javaConvertIntArrayToNSRangeValue(env, axTextRange) rangeValue];
}

- (NSString *)accessibilityStringForRange:(NSRange)range {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    GET_CACCESSIBLETEXT_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getStringForRange, sjc_CAccessibleText, "getStringForRange",
                 "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)Ljava/lang/String;", nil);
    jstring jstringForRange = (jstring)(*env)->CallStaticObjectMethod(env, sjc_CAccessibleText, jm_getStringForRange,
                            fAccessible, fComponent, range.location, range.length);
    CHECK_EXCEPTION();
    if (jstringForRange == NULL) return @"";
    NSString* str = JavaStringToNSString(env, jstringForRange);
    (*env)->DeleteLocalRef(env, jstringForRange);
    return str;
}

- (id)accessibilityValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    // cmcnote: inefficient to make three distinct JNI calls. Coalesce. radr://3951923
    GET_ACCESSIBLETEXT_METHOD_RETURN(@"");
    jobject axText = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                      sjm_getAccessibleText, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axText == NULL) return nil;
    (*env)->DeleteLocalRef(env, axText);

    GET_ACCESSIBLEEDITABLETEXT_METHOD_RETURN(nil);
    jobject axEditableText = (*env)->CallStaticObjectMethod(env, sjc_CAccessibleText,
                       sjm_getAccessibleEditableText, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axEditableText == NULL) return nil;

    DECLARE_STATIC_METHOD_RETURN(jm_getTextRange, sjc_CAccessibleText, "getTextRange",
                    "(Ljavax/accessibility/AccessibleEditableText;IILjava/awt/Component;)Ljava/lang/String;", nil);
    jobject jrange = (*env)->CallStaticObjectMethod(env, sjc_CAccessibleText, jm_getTextRange,
                       axEditableText, 0, getAxTextCharCount(env, axEditableText, fComponent), fComponent);
    CHECK_EXCEPTION();
    NSString *string = JavaStringToNSString(env, jrange);

    (*env)->DeleteLocalRef(env, jrange);
    (*env)->DeleteLocalRef(env, axEditableText);

    if (string == nil) string = @"";
    return string;
}

- (NSAccessibilitySubrole)accessibilitySubrole {
    if ([self accessibleIsPasswordText]) {
        return NSAccessibilitySecureTextFieldSubrole;
    }
    return nil;
}

- (NSRange)accessibilityRangeForIndex:(NSInteger)index {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(NSRangeFromString(@""));
    DECLARE_STATIC_METHOD_RETURN(jm_getRangeForIndex, sjc_CAccessibleText, "getRangeForIndex",
                    "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)[I", NSRangeFromString(@""));
    jintArray axTextRange = (jintArray)(*env)->CallStaticObjectMethod(env, sjc_CAccessibleText, jm_getRangeForIndex,
                              fAccessible, fComponent, index);
    CHECK_EXCEPTION();
    if (axTextRange == NULL) return NSRangeFromString(@"");

    return [javaConvertIntArrayToNSRangeValue(env, axTextRange) rangeValue];
}

- (NSAccessibilityRole)accessibilityRole {
    return [sRoles objectForKey:self.javaRole];
}

- (NSRange)accessibilityRangeForPosition:(NSPoint)point {
    point.y = [[[[self view] window] screen] frame].size.height - point.y; // flip into java screen coords (0 is at upper-left corner of screen)

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(NSRangeFromString(@""));
    DECLARE_STATIC_METHOD_RETURN(jm_getCharacterIndexAtPosition, sjc_CAccessibleText, "getCharacterIndexAtPosition",
                           "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)I", NSRangeFromString(@""));
    jint charIndex = (*env)->CallStaticIntMethod(env, sjc_CAccessibleText, jm_getCharacterIndexAtPosition,
                            fAccessible, fComponent, point.x, point.y);
    CHECK_EXCEPTION();
    if (charIndex == -1) return NSRangeFromString(@"");

    // AccessibleText.getIndexAtPoint returns -1 for an invalid point
    NSRange range = NSMakeRange(charIndex, 1); //range's length is 1 - one-character range
    return range;
}

- (NSString *)accessibilitySelectedText {
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getSelectedText, sjc_CAccessibleText, "getSelectedText",
              "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;", nil);
    jobject axText = (*env)->CallStaticObjectMethod(env, sjc_CAccessibleText, jm_getSelectedText,
                        fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axText == NULL) return @"";
    NSString* str = JavaStringToNSString(env, axText);
    (*env)->DeleteLocalRef(env, axText);
    return str;
}

- (NSRange)accessibilitySelectedTextRange {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(NSRangeFromString(@""));
    DECLARE_STATIC_METHOD_RETURN(jm_getSelectedTextRange, sjc_CAccessibleText, "getSelectedTextRange",
           "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)[I", NSRangeFromString(@""));
    jintArray axTextRange = (*env)->CallStaticObjectMethod(env, sjc_CAccessibleText,
                jm_getSelectedTextRange, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (axTextRange == NULL) return NSRangeFromString(@"");

    return [javaConvertIntArrayToNSRangeValue(env, axTextRange) rangeValue];
}

- (NSInteger)accessibilityNumberOfCharacters {
    // cmcnote: should coalesce these two calls - radr://3951923
    // also, static text doesn't always have accessibleText. if axText is null, should get the charcount of the accessibleName instead
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_ACCESSIBLETEXT_METHOD_RETURN(nil);
    jobject axText = (*env)->CallStaticObjectMethod(env, sjc_CAccessibility,
                     sjm_getAccessibleText, fAccessible, fComponent);
    CHECK_EXCEPTION();
    NSInteger num = getAxTextCharCount(env, axText, fComponent);
    (*env)->DeleteLocalRef(env, axText);
    return num;
}

- (NSInteger)accessibilityInsertionPointLineNumber {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS_RETURN(nil);
    DECLARE_STATIC_METHOD_RETURN(jm_getLineNumberForInsertionPoint, sjc_CAccessibleText,
             "getLineNumberForInsertionPoint", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)I", nil);
    jint row = (*env)->CallStaticIntMethod(env, sjc_CAccessibleText,
                  jm_getLineNumberForInsertionPoint, fAccessible, fComponent);
    CHECK_EXCEPTION();
    if (row < 0) return nil;
    return row;
}

- (void)setAccessibilitySelectedText:(NSString *)accessibilitySelectedText {
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jstring jstringValue = NSStringToJavaString(env, accessibilitySelectedText);
    GET_CACCESSIBLETEXT_CLASS();
    DECLARE_STATIC_METHOD(jm_setSelectedText, sjc_CAccessibleText, "setSelectedText",
                   "(Ljavax/accessibility/Accessible;Ljava/awt/Component;Ljava/lang/String;)V");
    (*env)->CallStaticVoidMethod(env, sjc_CAccessibleText, jm_setSelectedText,
              fAccessible, fComponent, jstringValue);
    CHECK_EXCEPTION();
}

- (void)setAccessibilitySelectedTextRange:(NSRange)accessibilitySelectedTextRange {
    jint startIndex = accessibilitySelectedTextRange.location;
    jint endIndex = startIndex + accessibilitySelectedTextRange.length;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    GET_CACCESSIBLETEXT_CLASS();
    DECLARE_STATIC_METHOD(jm_setSelectedTextRange, sjc_CAccessibleText, "setSelectedTextRange",
                  "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)V");
    (*env)->CallStaticVoidMethod(env, sjc_CAccessibleText, jm_setSelectedTextRange,
                  fAccessible, fComponent, startIndex, endIndex);
    CHECK_EXCEPTION();
}

- (BOOL)isAccessibilityEdited {
    return YES;
}

- (BOOL)isAccessibilityEnabled {
    return YES;
}

/*
* Other text methods
- (NSRange)accessibilitySharedCharacterRange;
- (NSArray *)accessibilitySharedTextUIElements;
- (NSData *)accessibilityRTFForRange:(NSRange)range;
- (NSRange)accessibilityStyleRangeForIndex:(NSInteger)index;
*/

@end

