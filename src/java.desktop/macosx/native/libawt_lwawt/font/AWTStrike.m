/*
 * Copyright (c) 2011, 2024, Oracle and/or its affiliates. All rights reserved.
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

#import "java_awt_geom_PathIterator.h"
#import "sun_font_CStrike.h"
#import "sun_font_CStrikeDisposer.h"
#import "CGGlyphImages.h"
#import "CGGlyphOutlines.h"
#import "CoreTextSupport.h"
#import "JNIUtilities.h"
#include "fontscalerdefs.h"
#import "LWCToolkit.h"

@implementation AWTStrike

static CGAffineTransform sInverseTX = { 1, 0, 0, -1, 0, 0 };

- (id) initWithFont:(AWTFont *)awtFont
                 tx:(CGAffineTransform)tx
           invDevTx:(CGAffineTransform)invDevTx
              style:(JRSFontRenderingStyle)style
            aaStyle:(jint)aaStyle
             fmHint:(jint)fmHint
subpixelResolutionX:(jint)subpixelResolutionX
subpixelResolutionY:(jint)subpixelResolutionY {

    self = [super init];
    if (self) {
        fAWTFont = [awtFont retain];
        fStyle = style;
        fAAStyle = aaStyle;
        fFmHint = fmHint;
        fSubpixelResolutionX = subpixelResolutionX;
        fSubpixelResolutionY = subpixelResolutionY;

        fTx = tx; // composited glyph and device transform

        fAltTx = tx;
        fAltTx.b *= -1;
        fAltTx.d *= -1;

        invDevTx.b *= -1;
        invDevTx.c *= -1;
        fFontTx = CGAffineTransformConcat(CGAffineTransformConcat(tx, invDevTx), sInverseTX);
        fDevTx = CGAffineTransformInvert(CGAffineTransformConcat(invDevTx, sInverseTX));

        // the "font size" is the square root of the determinant of the matrix
        fSize = sqrt(fabs(fFontTx.a * fFontTx.d - fFontTx.b * fFontTx.c));
    }
    return self;
}

- (void) dealloc {
    [fAWTFont release];
    fAWTFont = nil;

    [super dealloc];
}

+ (AWTStrike *) awtStrikeForFont:(AWTFont *)awtFont
                              tx:(CGAffineTransform)tx
                        invDevTx:(CGAffineTransform)invDevTx
                           style:(JRSFontRenderingStyle)style
                         aaStyle:(jint)aaStyle
                          fmHint:(jint)fmHint
             subpixelResolutionX:(jint)subpixelResolutionX
             subpixelResolutionY:(jint)subpixelResolutionY {

    return [[[AWTStrike alloc] initWithFont:awtFont
                                         tx:tx invDevTx:invDevTx
                                      style:style
                                    aaStyle:aaStyle
                                     fmHint:fmHint
                        subpixelResolutionX:subpixelResolutionX
                        subpixelResolutionY:subpixelResolutionY] autorelease];
}

@end


#define AWT_FONT_CLEANUP_SETUP \
    BOOL _fontThrowJavaException = NO;

#define AWT_FONT_CLEANUP_CHECK(a)                                       \
    if ((a) == NULL) {                                                  \
        _fontThrowJavaException = YES;                                  \
        goto cleanup;                                                   \
    }                                                                   \
    if ((*env)->ExceptionCheck(env)) {                                  \
        goto cleanup;                                                   \
    }

#define AWT_FONT_CLEANUP_FINISH                                         \
    if (_fontThrowJavaException == YES) {                               \
        char s[512];                                                    \
        snprintf(s, sizeof(s), "%s-%s:%d", __FILE__, __FUNCTION__, __LINE__);       \
        JNU_ThrowByName(env, "java/lang/RuntimeException", s);          \
    }


/*
 * Creates an affine transform from the corresponding doubles sent
 * from CStrike.getGlyphTx().
 */
static inline CGAffineTransform
GetTxFromDoubles(JNIEnv *env, jdoubleArray txArray)
{
    if (txArray == NULL) {
        return CGAffineTransformIdentity;
    }

    jdouble *txPtr = (*env)->GetPrimitiveArrayCritical(env, txArray, NULL);
    if (txPtr == NULL) {
        return CGAffineTransformIdentity;
    }

    CGAffineTransform tx =
        CGAffineTransformMake(txPtr[0], txPtr[1], txPtr[2],
                              txPtr[3], txPtr[4], txPtr[5]);
    tx = CGAffineTransformConcat(sInverseTX, tx);

    (*env)->ReleasePrimitiveArrayCritical(env, txArray, txPtr, JNI_ABORT);

    return tx;
}

/*
 * Class:     sun_font_CStrike
 * Method:    getNativeGlyphAdvance
 * Signature: (JI)F
 */
JNIEXPORT jfloat JNICALL
Java_sun_font_CStrike_getNativeGlyphAdvance
    (JNIEnv *env, jclass clazz, jlong awtStrikePtr, jint glyphCode)
{
    CGSize advance;
JNI_COCOA_ENTER(env);
    AWTStrike *awtStrike = (AWTStrike *)jlong_to_ptr(awtStrikePtr);
    AWTFont *awtFont = awtStrike->fAWTFont;

    // negative glyph codes are really unicodes, which were placed there by the mapper
    // to indicate we should use CoreText to substitute the character
    CGGlyph glyph;
    const CTFontRef fallback = CTS_CopyCTFallbackFontAndGlyphForJavaGlyphCode(awtFont, glyphCode, &glyph);
    CGGlyphImages_GetGlyphMetrics(fallback, &awtStrike->fAltTx, awtStrike->fSize, awtStrike->fStyle, &glyph, 1, NULL, &advance, IS_OSX_GT10_14);
    CFRelease(fallback);
    advance = CGSizeApplyAffineTransform(advance, awtStrike->fFontTx);
    if (!JRSFontStyleUsesFractionalMetrics(awtStrike->fStyle)) {
        advance.width = round(advance.width);
    }

JNI_COCOA_EXIT(env);
    return advance.width;
}

/*
 * Class:     sun_font_CStrike
 * Method:    getNativeGlyphImageBounds
 * Signature: (JJILjava/awt/geom/Rectangle2D/Float;DD)V
 */
JNIEXPORT void JNICALL
Java_sun_font_CStrike_getNativeGlyphImageBounds
    (JNIEnv *env, jclass clazz,
     jlong awtStrikePtr, jint glyphCode,
     jobject result /*Rectangle*/, jdouble x, jdouble y)
{
JNI_COCOA_ENTER(env);

    AWTStrike *awtStrike = (AWTStrike *)jlong_to_ptr(awtStrikePtr);
    AWTFont *awtFont = awtStrike->fAWTFont;

    CGAffineTransform tx = awtStrike->fAltTx;
    tx.tx += x;
    tx.ty += y;

    // negative glyph codes are really unicodes, which were placed there by the mapper
    // to indicate we should use CoreText to substitute the character
    CGGlyph glyph;
    const CTFontRef fallback = CTS_CopyCTFallbackFontAndGlyphForJavaGlyphCode(awtFont, glyphCode, &glyph);

    CGRect bbox;
    CGGlyphImages_GetGlyphMetrics(fallback, &tx, awtStrike->fSize, awtStrike->fStyle, &glyph, 1, &bbox, NULL, IS_OSX_GT10_14);
    CFRelease(fallback);

    // the origin of this bounding box is relative to the bottom-left corner baseline
    CGFloat decender = -bbox.origin.y;
    bbox.origin.y = -bbox.size.height + decender;

    // Rectangle2D.Float.setRect(float x, float y, float width, float height);
    DECLARE_CLASS(sjc_Rectangle2D_Float, "java/awt/geom/Rectangle2D$Float");    // cache class id for Rectangle
    DECLARE_METHOD(sjr_Rectangle2DFloat_setRect, sjc_Rectangle2D_Float, "setRect", "(FFFF)V");
    (*env)->CallVoidMethod(env, result, sjr_Rectangle2DFloat_setRect,
             (jfloat)bbox.origin.x, (jfloat)bbox.origin.y, (jfloat)bbox.size.width, (jfloat)bbox.size.height);
    CHECK_EXCEPTION();

JNI_COCOA_EXIT(env);
}

jobject getGlyphOutline(JNIEnv *env, AWTStrike *awtStrike,
                        CTFontRef font, CGGlyph glyph,
                        jdouble xPos, jdouble yPos)
{
    jobject generalPath = NULL;
    AWTPathRef path = NULL;
    jfloatArray pointCoords = NULL;
    jbyteArray pointTypes = NULL;

    DECLARE_CLASS_RETURN(jc_GeneralPath, "java/awt/geom/GeneralPath", NULL);
    DECLARE_METHOD_RETURN(jc_GeneralPath_ctor, jc_GeneralPath, "<init>", "(I[BI[FI)V", NULL);

AWT_FONT_CLEANUP_SETUP;

    // inverting the shear order and sign to compensate for the flipped coordinate system
    CGAffineTransform tx = awtStrike->fTx;
    tx.tx += xPos;
    tx.ty += yPos;

    // get the advance of this glyph
    CGSize advance;
    CTFontGetAdvancesForGlyphs(font, kCTFontDefaultOrientation, &glyph, &advance, 1);

    // Create AWTPath
    path = AWTPathCreate(CGSizeMake(xPos, yPos));
AWT_FONT_CLEANUP_CHECK(path);

    // Get the paths
    tx = awtStrike->fTx;
    tx = CGAffineTransformConcat(tx, sInverseTX);
    AWTGetGlyphOutline(&glyph, (NSFont *)font, &advance, &tx, 0, 1, &path);

    pointCoords = (*env)->NewFloatArray(env, path->fNumberOfDataElements);
AWT_FONT_CLEANUP_CHECK(pointCoords);

    (*env)->SetFloatArrayRegion(env, pointCoords, 0, path->fNumberOfDataElements, (jfloat*)path->fSegmentData);

    // Copy the pointTypes to the general path
    pointTypes = (*env)->NewByteArray(env, path->fNumberOfSegments);
AWT_FONT_CLEANUP_CHECK(pointTypes);

    (*env)->SetByteArrayRegion(env, pointTypes, 0, path->fNumberOfSegments, (jbyte*)path->fSegmentType);

    generalPath = (*env)->NewObject(env, jc_GeneralPath, jc_GeneralPath_ctor, java_awt_geom_PathIterator_WIND_NON_ZERO, pointTypes,
                    path->fNumberOfSegments, pointCoords, path->fNumberOfDataElements); // AWT_THREADING Safe (known object)

    // Cleanup
cleanup:
    if (path != NULL) {
        AWTPathFree(path);
        path = NULL;
    }

    if (pointCoords != NULL) {
        (*env)->DeleteLocalRef(env, pointCoords);
        pointCoords = NULL;
    }

    if (pointTypes != NULL) {
        (*env)->DeleteLocalRef(env, pointTypes);
        pointTypes = NULL;
    }

    AWT_FONT_CLEANUP_FINISH;
    return generalPath;
}

/*
 * Class:     sun_font_CStrike
 * Method:    getNativeGlyphOutline
 * Signature: (JJIDD)Ljava/awt/geom/GeneralPath;
 */
JNIEXPORT jobject JNICALL
Java_sun_font_CStrike_getNativeGlyphOutline
        (JNIEnv *env, jclass clazz,
         jlong awtStrikePtr, jint glyphCode, jdouble xPos, jdouble yPos) {
    jobject generalPath = NULL;

    JNI_COCOA_ENTER(env);
    AWT_FONT_CLEANUP_SETUP;

    AWTStrike *awtStrike = (AWTStrike *)jlong_to_ptr(awtStrikePtr);
    AWTFont *awtfont = awtStrike->fAWTFont;
    AWT_FONT_CLEANUP_CHECK(awtfont);

    // get the right font and glyph for this "Java GlyphCode"
    CGGlyph glyph;
    const CTFontRef font = CTS_CopyCTFallbackFontAndGlyphForJavaGlyphCode(awtfont, glyphCode, &glyph);

    generalPath = getGlyphOutline(env, awtStrike, font, glyph, xPos, yPos);

    cleanup:
    CFRelease(font);

    AWT_FONT_CLEANUP_FINISH;
    JNI_COCOA_EXIT(env);

    return generalPath;
}

// OpenType data is Big-Endian
#define GET_BE_INT32(data, i) CFSwapInt32BigToHost(*(const UInt32*) ((const UInt8*) (data) + (i)))
#define GET_BE_INT16(data, i) CFSwapInt16BigToHost(*(const UInt16*) ((const UInt8*) (data) + (i)))

static bool addBitmapRenderData(JNIEnv *env, AWTStrike *awtStrike,
                                CTFontRef font, CGGlyph glyph,
                                jdouble xPos, jdouble yPos, jobject result) {
    bool success = false;

    DECLARE_CLASS_RETURN(jc_GlyphRenderData, "sun/font/GlyphRenderData", false);
    DECLARE_METHOD_RETURN(GlyphRenderDataAddBitmap, jc_GlyphRenderData, "addBitmap", "(DDDDDDIIII[I)V", false);

    AWT_FONT_CLEANUP_SETUP;

    CTFontDescriptorRef descriptor = NULL;
    CGFontRef cgFont = CTFontCopyGraphicsFont(font, &descriptor);

    CFDataRef sbixTable = CGFontCopyTableForTag(cgFont, kCTFontTableSbix);
    if (sbixTable == NULL) {
        goto cleanup;
    }

    // Parse sbix table
    CFIndex sbixSize = CFDataGetLength(sbixTable);
    if (sbixSize < 8) {
        goto cleanup; // Corrupted table
    }
    const UInt8* sbix = CFDataGetBytePtr(sbixTable);
    UInt32 numStrikes = GET_BE_INT32(sbix, 4);
    if (8 + 4 * numStrikes > sbixSize) {
        goto cleanup; // Corrupted table
    }
    // Find last strike which has data for our glyph
    // Last is usually the biggest
    const UInt8* glyphData = NULL;
    UInt32 size;
    UInt16 ppem, ppi;
    for (int i = numStrikes - 1; i >= 0; i--) {
        const UInt8* strike = sbix + GET_BE_INT32(sbix, 8 + 4 * i);
        if (strike + 12 + 4 * glyph > sbix + sbixSize) {
            goto cleanup; // Corrupted table
        }
        UInt32 offset = GET_BE_INT32(strike, 4 + 4 * glyph);
        size = GET_BE_INT32(strike, 8 + 4 * glyph) - offset;
        if (size > 0) {
            ppem = GET_BE_INT16(strike, 0);
            ppi = GET_BE_INT16(strike, 2);
            glyphData = strike + offset;
            break;
        }
    }
    if (glyphData == NULL) {
        goto cleanup;
    }
    if (glyphData + 4 > sbix + sbixSize) {
        goto cleanup; // Corrupted table
    }

    // Read glyph data
    FourCharCode graphicType = GET_BE_INT32(glyphData, 4);
    glyphData += 8;
    size -= 8;
    if (glyphData + size > sbix + sbixSize) {
        goto cleanup; // Corrupted table
    }

    // Decode glyph image
    CGDataProviderRef dataProvider = CGDataProviderCreateWithData(NULL, glyphData, size, NULL);
    CGImageRef image = NULL;
    if (graphicType == 'jpg ') {
        image = CGImageCreateWithJPEGDataProvider(dataProvider, NULL, false, kCGRenderingIntentDefault);
    } else if (graphicType == 'png ') {
        image = CGImageCreateWithPNGDataProvider(dataProvider, NULL, false, kCGRenderingIntentDefault);
    }
    CGDataProviderRelease(dataProvider);

    if (image != NULL) {
        CGBitmapInfo info = CGImageGetBitmapInfo(image);
        size_t bits = CGImageGetBitsPerPixel(image);
        jint colorModel = -1;
        if (info & (kCGImageAlphaPremultipliedLast | kCGImageAlphaLast)) {
            colorModel = 0; // RGBA
        } else if (info & (kCGImageAlphaPremultipliedFirst | kCGImageAlphaFirst)) {
            colorModel = 1; // ARGB
        }
        if (colorModel != -1 && (info & kCGBitmapFloatComponents) == 0 && bits == 32) {
            size_t width = CGImageGetWidth(image);
            size_t height = CGImageGetHeight(image);
            size_t pitch = CGImageGetBytesPerRow(image) / 4;
            dataProvider = CGImageGetDataProvider(image);
            CFDataRef data = CGDataProviderCopyData(dataProvider);

            jbyteArray array = (*env)->NewIntArray(env, pitch * height);
            (*env)->SetIntArrayRegion(env, array, 0, pitch * height, (const jint*) CFDataGetBytePtr(data));
            CFRelease(data);

            double pointSize = 72.0 * ppem / ppi;
            double scale = 1.0 / pointSize;
            font = CTFontCreateWithGraphicsFont(cgFont, pointSize, NULL, descriptor);
            CGRect bbox = CTFontGetBoundingRectsForGlyphs(font, kCTFontOrientationDefault, &glyph, NULL, 1);
            CFRelease(font);
            double tx = bbox.origin.x + xPos * pointSize / awtStrike->fSize;
            double ty = -bbox.origin.y - (double) height + yPos * pointSize / awtStrike->fSize;

            jdouble m00 = awtStrike->fTx.a * scale, m10 = awtStrike->fTx.b * scale;
            jdouble m01 = -awtStrike->fTx.c * scale, m11 = -awtStrike->fTx.d * scale;
            jdouble m02 = m00 * tx + m01 * ty, m12 = m10 * tx + m11 * ty;

            (*env)->CallVoidMethod(env, result, GlyphRenderDataAddBitmap,
                                   m00, m10, m01, m11, m02, m12,
                                   width, height, pitch, 0, array);
            success = true;
        }
        CGImageRelease(image);
    }

    // Cleanup
    cleanup:
    if (sbixTable) {
        CFRelease(sbixTable);
    }
    if (cgFont) {
        CFRelease(cgFont);
    }
    if (descriptor) {
        CFRelease(descriptor);
    }

    AWT_FONT_CLEANUP_FINISH;
    return success;
}

/*
 * Class:     sun_font_CStrike
 * Method:    getNativeGlyphRenderData
 * Signature: (JIDDLsun/font/GlyphRenderData;)V
 */
JNIEXPORT void JNICALL
Java_sun_font_CStrike_getNativeGlyphRenderData
        (JNIEnv *env, jclass clazz,
         jlong awtStrikePtr, jint glyphCode, jdouble xPos, jdouble yPos, jobject result)
{
    JNI_COCOA_ENTER(env);

    DECLARE_CLASS(jc_GlyphRenderData, "sun/font/GlyphRenderData");
    DECLARE_FIELD(GlyphRenderDataOutline, jc_GlyphRenderData, "outline", "Ljava/awt/geom/GeneralPath;")

    AWT_FONT_CLEANUP_SETUP;

    AWTStrike *awtStrike = (AWTStrike *)jlong_to_ptr(awtStrikePtr);
    AWTFont *awtfont = awtStrike->fAWTFont;
    AWT_FONT_CLEANUP_CHECK(awtfont);

    // get the right font and glyph for this "Java GlyphCode"
    CGGlyph glyph;
    const CTFontRef font = CTS_CopyCTFallbackFontAndGlyphForJavaGlyphCode(awtfont, glyphCode, &glyph);

    if (!addBitmapRenderData(env, awtStrike, font, glyph, xPos, yPos, result)) {
        jobject gp = getGlyphOutline(env, awtStrike, font, glyph, xPos, yPos);
        if (gp != NULL) {
            (*env)->SetObjectField(env, result, GlyphRenderDataOutline, gp);
        }
    }

    cleanup:
    CFRelease(font);

    AWT_FONT_CLEANUP_FINISH;
    JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_font_CStrike
 * Method:    getNativeGlyphOutlineBounds
 * Signature: (JILjava/awt/geom/Rectangle2D/Float;DD)V
 */
JNIEXPORT void JNICALL Java_sun_font_CStrike_getNativeGlyphOutlineBounds
        (JNIEnv *env, jclass clazz, jlong awtStrikePtr, jint glyphCode,
         jfloatArray rectData)
{
    JNI_COCOA_ENTER(env);
    AWTStrike *awtStrike = (AWTStrike *)jlong_to_ptr(awtStrikePtr);
    AWTFont *awtfont = awtStrike->fAWTFont;

    AWT_FONT_CLEANUP_SETUP;
    AWT_FONT_CLEANUP_CHECK(awtfont);

    // get the right font and glyph for this "Java GlyphCode"
    CGGlyph glyph;
    const CTFontRef font = CTS_CopyCTFallbackFontAndGlyphForJavaGlyphCode(
            awtfont, glyphCode, &glyph);

    CGRect bbox = CTFontGetBoundingRectsForGlyphs(
        font, kCTFontOrientationDefault, &glyph, NULL, 1);

    CGAffineTransform tx = CGAffineTransformConcat(awtStrike->fTx,
                                                   sInverseTX);

    bbox =  CGRectApplyAffineTransform (bbox, tx);
    CFRelease(font);
    jfloat *rawRectData =
        (*env)->GetPrimitiveArrayCritical(env, rectData, NULL);

    if (CGRectIsNull(bbox)) {
        rawRectData[0] = 0.0f;
        rawRectData[1] = 0.0f;
        rawRectData[2] = 0.0f;
        rawRectData[3] = 0.0f;
    } else {
        rawRectData[0] = (jfloat) bbox.origin.x;
        rawRectData[1] = (jfloat) (-bbox.origin.y - bbox.size.height);
        rawRectData[2] = (jfloat) bbox.size.width;
        rawRectData[3] = (jfloat) bbox.size.height;
    }

    (*env)->ReleasePrimitiveArrayCritical(env, rectData, rawRectData, 0);
    // Cleanup
    cleanup:
        AWT_FONT_CLEANUP_FINISH;

    JNI_COCOA_EXIT(env);
}
/*
 * Class:     sun_font_CStrike
 * Method:    getGlyphImagePtrsNative
 * Signature: (JJ[J[II)V
 */
JNIEXPORT void JNICALL
Java_sun_font_CStrike_getGlyphImagePtrsNative
    (JNIEnv *env, jclass clazz,
     jlong awtStrikePtr, jlongArray glyphInfoLongArray,
     jintArray glyphCodes, jint len)
{
JNI_COCOA_ENTER(env);

    AWTStrike *awtStrike = (AWTStrike *)jlong_to_ptr(awtStrikePtr);

    jlong *glyphInfos =
        (*env)->GetPrimitiveArrayCritical(env, glyphInfoLongArray, NULL);

    jint *rawGlyphCodes =
            (*env)->GetPrimitiveArrayCritical(env, glyphCodes, NULL);
    @try {
        if (rawGlyphCodes != NULL && glyphInfos != NULL) {
            CGGlyphImages_GetGlyphImagePtrs(glyphInfos, awtStrike,
                    rawGlyphCodes, len);
        }
    }
    @finally {
        if (rawGlyphCodes != NULL) {
            (*env)->ReleasePrimitiveArrayCritical(env, glyphCodes,
                                                  rawGlyphCodes, JNI_ABORT);
        }
        if (glyphInfos != NULL) {
            // Do not use JNI_COMMIT, as that will not free the buffer copy
            // when +ProtectJavaHeap is on.
            (*env)->ReleasePrimitiveArrayCritical(env, glyphInfoLongArray,
                                                  glyphInfos, 0);
        }
    }

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_font_CStrike
 * Method:    createNativeStrikePtr
 * Signature: (J[D[DII)J
 */
JNIEXPORT jlong JNICALL Java_sun_font_CStrike_createNativeStrikePtr
(JNIEnv *env, jclass clazz, jlong nativeFontPtr, jdoubleArray glyphTxArray, jdoubleArray invDevTxArray,
 jint aaStyle, jint fmHint, jint subpixelResolutionX, jint subpixelResolutionY)
{
    AWTStrike *awtStrike = nil;
JNI_COCOA_ENTER(env);

    AWTFont *awtFont = (AWTFont *)jlong_to_ptr(nativeFontPtr);
    JRSFontRenderingStyle style = JRSFontGetRenderingStyleForHints(fmHint, aaStyle);

    CGAffineTransform glyphTx = GetTxFromDoubles(env, glyphTxArray);
    CGAffineTransform invDevTx = GetTxFromDoubles(env, invDevTxArray);

    awtStrike = [AWTStrike awtStrikeForFont:awtFont tx:glyphTx invDevTx:invDevTx style:style
                 aaStyle:aaStyle fmHint:fmHint subpixelResolutionX:subpixelResolutionX subpixelResolutionY:subpixelResolutionY]; // autoreleased

    if (awtStrike)
    {
        CFRetain(awtStrike); // GC
    }

JNI_COCOA_EXIT(env);
    return ptr_to_jlong(awtStrike);
}

/*
 * Class:     sun_font_CStrike
 * Method:    disposeNativeStrikePtr
 * Signature: (J)V
 */
JNIEXPORT void JNICALL
Java_sun_font_CStrike_disposeNativeStrikePtr
    (JNIEnv *env, jclass clazz, jlong awtStrike)
{
JNI_COCOA_ENTER(env);

    if (awtStrike) {
        CFRelease((AWTStrike *)jlong_to_ptr(awtStrike)); // GC
    }

JNI_COCOA_EXIT(env);
}

/*
 * Class:     sun_font_CStrike
 * Method:    getFontMetrics
 * Signature: (J)Lsun/font/StrikeMetrics;
 */
JNIEXPORT jobject JNICALL
Java_sun_font_CStrike_getFontMetrics
    (JNIEnv *env, jclass clazz, jlong awtStrikePtr)
{
    jobject metrics = NULL;

JNI_COCOA_ENTER(env);
    AWT_FONT_CLEANUP_SETUP;

    AWTFont *awtfont = ((AWTStrike *)jlong_to_ptr(awtStrikePtr))->fAWTFont;
    AWT_FONT_CLEANUP_CHECK(awtfont);

    CGFontRef cgFont = awtfont->fNativeCGFont;

    jfloat ay=0.0, dy=0.0, mx=0.0, ly=0.0;
    int unitsPerEm = CGFontGetUnitsPerEm(cgFont);
    CGFloat scaleX = (1.0 / unitsPerEm);
    CGFloat scaleY = (1.0 / unitsPerEm);

    // Ascent
    ay = -(CGFloat)CGFontGetAscent(cgFont) * scaleY;

    // Descent
    dy = -(CGFloat)CGFontGetDescent(cgFont) * scaleY;

    // Leading
    ly = (CGFloat)CGFontGetLeading(cgFont) * scaleY;

    // Max Advance for Font Direction (Strictly horizontal)
    mx = [awtfont->fFont maximumAdvancement].width;

    /*
     * ascent:   no need to set ascentX - it will be zero.
     * descent:  no need to set descentX - it will be zero.
     * baseline: old releases "made up" a number and also seemed to
     *           make it up for "X" and set "Y" to 0.
     * leadingX: no need to set leadingX - it will be zero.
     * leadingY: made-up number, but being compatible with what 1.4.x did.
     * advance:  no need to set yMaxLinearAdvanceWidth - it will be zero.
     */

    DECLARE_CLASS_RETURN(sjc_StrikeMetrics, "sun/font/StrikeMetrics", NULL);
    DECLARE_METHOD_RETURN(strikeMetricsCtr, sjc_StrikeMetrics, "<init>", "(FFFFFFFFFF)V", NULL);
    metrics = (*env)->NewObject(env, sjc_StrikeMetrics, strikeMetricsCtr,
                           0.0, ay, 0.0, dy, 1.0,
                           0.0, 0.0, ly, mx, 0.0);

cleanup:
    AWT_FONT_CLEANUP_FINISH;
JNI_COCOA_EXIT(env);

    return metrics;
}

extern void AccelGlyphCache_RemoveAllInfos(GlyphInfo* glyph);
/*
 * Class:     sun_font_CStrikeDisposer
 * Method:    removeGlyphInfoFromCache
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_sun_font_CStrikeDisposer_removeGlyphInfoFromCache
(JNIEnv *env, jclass cls, jlong glyphInfo)
{
    JNI_COCOA_ENTER(env);

    AccelGlyphCache_RemoveAllCellInfos((GlyphInfo*)jlong_to_ptr(glyphInfo));

    JNI_COCOA_EXIT(env);
}
