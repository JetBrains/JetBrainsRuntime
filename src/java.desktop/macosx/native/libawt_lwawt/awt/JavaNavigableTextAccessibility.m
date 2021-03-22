// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

#import "JavaNavigableTextAccessibility.h"
#import "JavaAccessibilityUtilities.h"
#import "ThreadUtilities.h"
#import <JavaNativeFoundation/JavaNativeFoundation.h>

static JNF_CLASS_CACHE(sjc_CAccessibleText, "sun/lwawt/macosx/CAccessibleText");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleText, sjc_CAccessibility, "getAccessibleText", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleText;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleEditableText, sjc_CAccessibleText, "getAccessibleEditableText", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljavax/accessibility/AccessibleEditableText;");
static JNF_STATIC_MEMBER_CACHE(sjm_getAccessibleName, sjc_CAccessibility, "getAccessibleName", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");

@implementation JavaNavigableTextAccessibility

- (NSString *)getPlatformAxElementClassName {
    return @"PlatformAxNavigableText";
}

- (NSString *)accessibleValue {
    JNIEnv *env = [ThreadUtilities getJNIEnv];

    // cmcnote: inefficient to make three distinct JNI calls. Coalesce. radr://3951923
    jobject axText = JNFCallStaticObjectMethod(env, sjm_getAccessibleText, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axText == NULL) return nil;
    (*env)->DeleteLocalRef(env, axText);

    jobject axEditableText = JNFCallStaticObjectMethod(env, sjm_getAccessibleEditableText, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axEditableText == NULL) return nil;

    static JNF_STATIC_MEMBER_CACHE(jm_getTextRange, sjc_CAccessibleText, "getTextRange", "(Ljavax/accessibility/AccessibleEditableText;IILjava/awt/Component;)Ljava/lang/String;");
    jobject jrange = JNFCallStaticObjectMethod(env, jm_getTextRange, axEditableText, 0, getAxTextCharCount(env, axEditableText, fComponent), fComponent);
    NSString *string = JNFJavaToNSString(env, jrange); // AWT_THREADING Safe (AWTRunLoop)

    (*env)->DeleteLocalRef(env, jrange);
    (*env)->DeleteLocalRef(env, axEditableText);

    if (string == nil) string = @"";
    return string;
}

- (NSValue *)accessibleBoundsForRange:(NSRange)range { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getBoundsForRange, sjc_CAccessibleText, "getBoundsForRange", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)[D");
    jdoubleArray axBounds = (jdoubleArray)JNFCallStaticObjectMethod(env, jm_getBoundsForRange, fAccessible, fComponent, range.location, range.length); // AWT_THREADING Safe (AWTRunLoop)
    if (axBounds == NULL) return nil;

    // We cheat because we know that the array is 4 elements long (x, y, width, height)
    jdouble *values = (*env)->GetDoubleArrayElements(env, axBounds, 0);
    if (values == NULL) {
        // Note: Java will not be on the stack here so a java exception can't happen and no need to call ExceptionCheck.
        NSLog(@"%s failed calling GetDoubleArrayElements", __FUNCTION__);
        return nil;
    };
    NSRect bounds;
    bounds.origin.x = values[0];
    bounds.origin.y = [[[[self view] window] screen] frame].size.height - values[1] - values[3]; //values[1] is y-coord from top-left of screen. Flip. Account for the height (values[3]) when flipping
    bounds.size.width = values[2];
    bounds.size.height = values[3];
    NSValue *result = [NSValue valueWithRect:bounds];
    (*env)->ReleaseDoubleArrayElements(env, axBounds, values, 0);
    return result;
}

- (NSNumber *)accessibleLineForIndex:(NSInteger)index { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getLineNumberForIndex, sjc_CAccessibleText, "getLineNumberForIndex", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)I");
    jint row = JNFCallStaticIntMethod(env, jm_getLineNumberForIndex, fAccessible, fComponent, index); // AWT_THREADING Safe (AWTRunLoop)
    if (row < 0) return nil;
    return [NSNumber numberWithInt:row];
}

- (NSValue *)accessibleRangeForLine:(NSInteger)line { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getRangeForLine, sjc_CAccessibleText, "getRangeForLine", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)[I");
    jintArray axTextRange = (jintArray)JNFCallStaticObjectMethod(env, jm_getRangeForLine, fAccessible, fComponent, line); // AWT_THREADING Safe (AWTRunLoop)
    if (axTextRange == NULL) return nil;

    return javaConvertIntArrayToNSRangeValue(env,axTextRange);
}

- (NSString *)accessibleStringForRange:(NSRange)range { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getStringForRange, sjc_CAccessibleText, "getStringForRange", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)Ljava/lang/String;");
    jstring jstringForRange = (jstring)JNFCallStaticObjectMethod(env, jm_getStringForRange, fAccessible, fComponent, range.location, range.length); // AWT_THREADING Safe (AWTRunLoop)

    if (jstringForRange == NULL) return @"";
    NSString* str = JNFJavaToNSString(env, jstringForRange);
    (*env)->DeleteLocalRef(env, jstringForRange);
    return str;
}

- (NSValue *)accessibleRangeForIndex:(NSInteger)index { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getRangeForIndex, sjc_CAccessibleText, "getRangeForIndex", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;I)[I");
    jintArray axTextRange = (jintArray)JNFCallStaticObjectMethod(env, jm_getRangeForIndex, fAccessible, fComponent, index); // AWT_THREADING Safe (AWTRunLoop)
    if (axTextRange == NULL) return nil;

    return javaConvertIntArrayToNSRangeValue(env, axTextRange);
}

- (NSValue *)accessibleRangeForPosition:(NSPoint)point { 
    point.y = [[[[self view] window] screen] frame].size.height - point.y; // flip into java screen coords (0 is at upper-left corner of screen)

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getCharacterIndexAtPosition, sjc_CAccessibleText, "getCharacterIndexAtPosition", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)I");
    jint charIndex = JNFCallStaticIntMethod(env, jm_getCharacterIndexAtPosition, fAccessible, fComponent, point.x, point.y); // AWT_THREADING Safe (AWTRunLoop)
    if (charIndex == -1) return nil;

    // AccessibleText.getIndexAtPoint returns -1 for an invalid point
    NSRange range = NSMakeRange(charIndex, 1); //range's length is 1 - one-character range
    return [NSValue valueWithRange:range];
}

- (NSString *)accessibleSelectedText { 
    JNIEnv* env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getSelectedText, sjc_CAccessibleText, "getSelectedText", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)Ljava/lang/String;");
    jobject axText = JNFCallStaticObjectMethod(env, jm_getSelectedText, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axText == NULL) return @"";
    NSString* str = JNFJavaToNSString(env, axText);
    (*env)->DeleteLocalRef(env, axText);
    return str;
}

- (NSValue *)accessibleSelectedTextRange { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getSelectedTextRange, sjc_CAccessibleText, "getSelectedTextRange", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)[I");
    jintArray axTextRange = JNFCallStaticObjectMethod(env, jm_getSelectedTextRange, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axTextRange == NULL) return nil;

    return javaConvertIntArrayToNSRangeValue(env, axTextRange);
}

- (NSNumber *)accessibleNumberOfCharacters { 
    // cmcnote: should coalesce these two calls - radr://3951923
    // also, static text doesn't always have accessibleText. if axText is null, should get the charcount of the accessibleName instead
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jobject axText = JNFCallStaticObjectMethod(env, sjm_getAccessibleText, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    NSNumber* num = [NSNumber numberWithInt:getAxTextCharCount(env, axText, fComponent)];
    (*env)->DeleteLocalRef(env, axText);
    return num;
}

- (NSNumber *)accessibleInsertionPointLineNumber { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_getLineNumberForInsertionPoint, sjc_CAccessibleText, "getLineNumberForInsertionPoint", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;)I");
    jint row = JNFCallStaticIntMethod(env, jm_getLineNumberForInsertionPoint, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (row < 0) return nil;
    return [NSNumber numberWithInt:row];
}

- (BOOL)accessibleIsValueSettable {
    // if text is enabled and editable, it's settable (according to NSCellTextAttributesAccessibility)
    BOOL isEnabled = [(NSNumber *)[self accessibilityEnabledAttribute] boolValue];
    if (!isEnabled) return NO;

    JNIEnv* env = [ThreadUtilities getJNIEnv];
    jobject axEditableText = JNFCallStaticObjectMethod(env, sjm_getAccessibleEditableText, fAccessible, fComponent); // AWT_THREADING Safe (AWTRunLoop)
    if (axEditableText == NULL) return NO;
    (*env)->DeleteLocalRef(env, axEditableText);
    return YES;
}

- (BOOL)accessibleIsPasswordText {
    return [[self javaRole] isEqualToString:@"passwordtext"];
}

- (void)accessibleSetSelectedText:(NSString *)accessibilitySelectedText { 
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    jstring jstringValue = JNFNSToJavaString(env, accessibilitySelectedText);
    static JNF_STATIC_MEMBER_CACHE(jm_setSelectedText, sjc_CAccessibleText, "setSelectedText", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;Ljava/lang/String;)V");
    JNFCallStaticVoidMethod(env, jm_setSelectedText, fAccessible, fComponent, jstringValue); // AWT_THREADING Safe (AWTRunLoop)
}

- (void)accessibleSetSelectedTextRange:(NSRange)accessibilitySelectedTextRange {
    jint startIndex = accessibilitySelectedTextRange.location;
    jint endIndex = startIndex + accessibilitySelectedTextRange.length;

    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_STATIC_MEMBER_CACHE(jm_setSelectedTextRange, sjc_CAccessibleText, "setSelectedTextRange", "(Ljavax/accessibility/Accessible;Ljava/awt/Component;II)V");
    JNFCallStaticVoidMethod(env, jm_setSelectedTextRange, fAccessible, fComponent, startIndex, endIndex); // AWT_THREADING Safe (AWTRunLoop)
}

@end

@implementation PlatformAxNavigableText

- (NSRect)accessibilityFrameForRange:(NSRange)range {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleBoundsForRange:range] rectValue];
}

- (NSInteger)accessibilityLineForIndex:(NSInteger)index {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleLineForIndex:index] integerValue];
}

- (NSRange)accessibilityRangeForLine:(NSInteger)line {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleRangeForLine:line] rangeValue];
}

- (NSString *)accessibilityStringForRange:(NSRange)range {
    return [(JavaNavigableTextAccessibility *)[self javaBase] accessibleStringForRange:range];
}

- (id)accessibilityValue {
    return [(JavaNavigableTextAccessibility *)[self javaBase] accessibleValue];
}

- (NSAccessibilitySubrole)accessibilitySubrole {
    if ([(JavaNavigableTextAccessibility *)[self javaBase] accessibleIsPasswordText]) {
        return NSAccessibilitySecureTextFieldSubrole;
    }
    return nil;
}

- (NSRange)accessibilityRangeForIndex:(NSInteger)index {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleRangeForIndex:index] rangeValue];
}

- (NSAccessibilityRole)accessibilityRole {
    return [sRoles objectForKey:[self javaBase].javaRole];
}

- (NSRange)accessibilityRangeForPosition:(NSPoint)point {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleRangeForPosition:point] rangeValue];
}

- (NSString *)accessibilitySelectedText {
    return [(JavaNavigableTextAccessibility *)[self javaBase] accessibleSelectedText];
}

- (NSRange)accessibilitySelectedTextRange {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleSelectedTextRange] rangeValue];
}

- (NSInteger)accessibilityNumberOfCharacters {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleNumberOfCharacters] integerValue];
}

- (NSInteger)accessibilityInsertionPointLineNumber {
    return [[(JavaNavigableTextAccessibility *)[self javaBase] accessibleInsertionPointLineNumber] integerValue];
}

- (void)setAccessibilitySelectedText:(NSString *)accessibilitySelectedText {
    [(JavaNavigableTextAccessibility *)[self javaBase] accessibleSetSelectedText:accessibilitySelectedText];
}

- (void)setAccessibilitySelectedTextRange:(NSRange)accessibilitySelectedTextRange {
    [(JavaNavigableTextAccessibility *)[self javaBase] accessibleSetSelectedTextRange:accessibilitySelectedTextRange];
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

