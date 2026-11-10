/*
 * Copyright (c) 2008, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#import "JNIUtilities.h"

#import "AWTFont.h"
#import "CoreTextSupport.h"

#import "sun_font_CCharToGlyphMapper.h"
#import "sun_font_CCompositeGlyphMapper.h"

/*
 * Class:     sun_font_CCharToGlyphMapper
 * Method:    countGlyphs
 * Signature: (J)I
 */
JNIEXPORT jint JNICALL
Java_sun_font_CCharToGlyphMapper_countGlyphs
    (JNIEnv *env, jclass clazz, jlong awtFontPtr)
{
    jint numGlyphs = 0;

JNI_COCOA_ENTER(env);

    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(awtFontPtr);
    numGlyphs = [awtFont->fFont numberOfGlyphs];

JNI_COCOA_EXIT(env);

    return numGlyphs;
}

static inline void
GetGlyphsFromUnicodes(JNIEnv *env, AWTFont *awtFont,
                      jint count, UniChar *unicodes,
                      CGGlyph *cgGlyphs, jintArray glyphs)
{
    jint *glyphCodeInts = (*env)->GetPrimitiveArrayCritical(env, glyphs, 0);

    if (glyphCodeInts != NULL) {
        CTS_GetGlyphsAsIntsForCharacters(awtFont, unicodes,
                                         cgGlyphs, glyphCodeInts, count);

        // Do not use JNI_COMMIT, as that will not free the buffer copy
        // when +ProtectJavaHeap is on.
        (*env)->ReleasePrimitiveArrayCritical(env, glyphs, glyphCodeInts, 0);
    }
}

static inline void
AllocateGlyphBuffer(JNIEnv *env, AWTFont *awtFont,
                    jint count, UniChar *unicodes, jintArray glyphs)
{
    if (count < MAX_STACK_ALLOC_GLYPH_BUFFER_SIZE) {
        CGGlyph cgGlyphs[count];
        GetGlyphsFromUnicodes(env, awtFont, count, unicodes, cgGlyphs, glyphs);
    } else {
        CGGlyph *cgGlyphs = (CGGlyph *)malloc(count * sizeof(CGGlyph));
        GetGlyphsFromUnicodes(env, awtFont, count, unicodes, cgGlyphs, glyphs);
        free(cgGlyphs);
    }
}

/*
 * Class:     sun_font_CCharToGlyphMapper
 * Method:    nativeCharsToGlyphs
 * Signature: (JI[C[I)V
 */
JNIEXPORT void JNICALL
Java_sun_font_CCharToGlyphMapper_nativeCharsToGlyphs
    (JNIEnv *env, jclass clazz,
     jlong awtFontPtr, jint count, jcharArray unicodes, jintArray glyphs)
{
JNI_COCOA_ENTER(env);

    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(awtFontPtr);

    // check the array size
    jint len = (*env)->GetArrayLength(env, glyphs);
    if (len < count) {
        count = len;
    }

    jchar *unicodesAsChars =
        (*env)->GetPrimitiveArrayCritical(env, unicodes, NULL);

    if (unicodesAsChars != NULL) {
        AllocateGlyphBuffer(env, awtFont, count,
                           (UniChar *)unicodesAsChars, glyphs);

        (*env)->ReleasePrimitiveArrayCritical(env, unicodes,
                                              unicodesAsChars, JNI_ABORT);
    }

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_font_CCompositeGlyphMapper
 * Method:    nativeCodePointToGlyph
 * Signature: (JII[Ljava/lang/String;)I
 */
JNIEXPORT jint JNICALL
Java_sun_font_CCompositeGlyphMapper_nativeCodePointToGlyph
(JNIEnv *env, jclass clazz, jlong awtFontPtr, jint codePoint, jint variationSelector, jobjectArray resultArray)
{
JNI_COCOA_ENTER(env);
    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(awtFontPtr);
    CFStringRef fontNames[] = {NULL, NULL};
    CGGlyph glyph = CTS_CopyGlyphAndFontNamesForCodePoint(awtFont, (UnicodeScalarValue)codePoint,
                                                          (UnicodeScalarValue)variationSelector, fontNames);
    if (glyph > 0) {
        jstring fontName = NSStringToJavaString(env, (NSString *)fontNames[0]);
        (*env)->SetObjectArrayElement(env, resultArray, 0, fontName);
        jstring fontFamilyName = NSStringToJavaString(env, (NSString *)fontNames[1]);
        (*env)->SetObjectArrayElement(env, resultArray, 1, fontFamilyName);
    }
    if (fontNames[0]) CFRelease(fontNames[0]);
    if (fontNames[1]) CFRelease(fontNames[1]);
    return glyph;
JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_font_CCascadeGlyphMapper
 * Method:    nativeCascadeGlyph
 * Signature: (Ljava/lang/String;II[Ljava/lang/String;)I
 *
 * CoreText's cascade from a created font (Font.createFont) for a code point the font lacks, as the platform's text
 * falls back from a font: CTFontCreateForString with a CTFont of the font's file at the index (one per file and
 * index, kept), the glyph in the font it picks and that font's PostScript and family names; 0 when it picks none.
 */
JNIEXPORT jint JNICALL
Java_sun_font_CCascadeGlyphMapper_nativeCascadeGlyph
(JNIEnv *env, jclass clazz, jstring path, jint index, jint codePoint, jobjectArray resultArray)
{
    jint result = 0;
JNI_COCOA_ENTER(env);
    static NSMutableDictionary *bases = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ bases = [[NSMutableDictionary alloc] init]; });
    NSString *file = JavaStringToNSString(env, path);
    NSString *key = [NSString stringWithFormat:@"%d:%@", (int) index, file];
    CTFontRef base = NULL;
    @synchronized (bases) {
        id known = bases[key];
        if (known == nil) {
            CFArrayRef descriptors = CTFontManagerCreateFontDescriptorsFromURL((CFURLRef) [NSURL fileURLWithPath:file]);
            if (descriptors != NULL && index >= 0 && index < CFArrayGetCount(descriptors)) {
                CTFontRef font = CTFontCreateWithFontDescriptor((CTFontDescriptorRef) CFArrayGetValueAtIndex(descriptors, index), 12.0, NULL);
                if (font != NULL) {
                    bases[key] = (id) font;
                    CFRelease(font);
                }
            }
            if (descriptors != NULL) CFRelease(descriptors);
            if (bases[key] == nil) bases[key] = [NSNull null];
            known = bases[key];
        }
        if (known != [NSNull null]) base = (CTFontRef) CFRetain((CFTypeRef) known);
    }
    if (base != NULL) {
        UniChar chars[2];
        CFIndex count = 1;
        if (codePoint >= 0x10000) {
            const int value = codePoint - 0x10000;
            chars[0] = (UniChar) (0xD800 | (value >> 10));
            chars[1] = (UniChar) (0xDC00 | (value & 0x3FF));
            count = 2;
        } else {
            chars[0] = (UniChar) codePoint;
        }
        CFStringRef string = CFStringCreateWithCharacters(kCFAllocatorDefault, chars, count);
        CTFontRef fallback = string != NULL ? CTFontCreateForString(base, string, CFRangeMake(0, count)) : NULL;
        if (string != NULL) CFRelease(string);
        if (fallback != NULL) {
            CGGlyph glyphs[2] = { 0, 0 };
            CFStringRef baseName = CTFontCopyPostScriptName(base), name = CTFontCopyPostScriptName(fallback);
            if (!CFEqual(baseName, name) && CTFontGetGlyphsForCharacters(fallback, chars, glyphs, count) && glyphs[0] != 0) {
                CFStringRef family = CTFontCopyFamilyName(fallback);
                (*env)->SetObjectArrayElement(env, resultArray, 0, NSStringToJavaString(env, (NSString *) name));
                (*env)->SetObjectArrayElement(env, resultArray, 1, NSStringToJavaString(env, (NSString *) family));
                CFRelease(family);
                result = glyphs[0];
            }
            CFRelease(baseName);
            CFRelease(name);
            CFRelease(fallback);
        }
        CFRelease(base);
    }
JNI_COCOA_EXIT(env);
    return result;
}
