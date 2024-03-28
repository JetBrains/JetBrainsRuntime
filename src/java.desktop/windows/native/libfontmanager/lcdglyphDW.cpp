/*
 * Copyright (c) 2016 JetBrains s.r.o.
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

#include <stdio.h>
#include <malloc.h>
#include <math.h>
#include <windows.h>
#include <winuser.h>
#include <Dwrite.h>

#include <jni.h>
#include <jni_util.h>
#include <jlong_md.h>
#include <jdk_util.h>
#include <sizecalc.h>
#include <sun_font_FileFontStrike.h>

#include "fontscalerdefs.h"

extern "C" {

#define FREE \
    if (target != NULL) { \
        target->Release(); \
    }\
    if (params != NULL) { \
        params->Release(); \
    }\
    if (defaultParams != NULL) { \
        defaultParams->Release(); \
    }\
    if (face != NULL) { \
        face->Release(); \
    }\
    if (font != NULL) { \
        font->Release(); \
    }\
    if (interop != NULL) { \
        interop->Release(); \
    }\
    if (factory != NULL) { \
        factory->Release(); \
    }

#define FREE_AND_RETURN \
    FREE\
    return (jlong)0;

typedef HRESULT (WINAPI *DWriteCreateFactoryType)(DWRITE_FACTORY_TYPE, REFIID, IUnknown**);

static BOOL checkedForDirectWriteAvailability = FALSE;
static DWriteCreateFactoryType fDWriteCreateFactory = NULL;

JNIEXPORT jboolean JNICALL
Java_sun_font_FileFontStrike_isDirectWriteAvailable(JNIEnv *env, jclass unused) {
    if (!checkedForDirectWriteAvailability) {
        checkedForDirectWriteAvailability = TRUE;
        HMODULE hDwrite = JDK_LoadSystemLibrary("Dwrite.dll");
        if (hDwrite) {
            fDWriteCreateFactory = (DWriteCreateFactoryType)GetProcAddress(hDwrite, "DWriteCreateFactory");
        }
    }
    return fDWriteCreateFactory != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_sun_font_FileFontStrike__1getGlyphImageFromWindowsUsingDirectWrite
(JNIEnv *env, jobject unused, jstring fontFamily, jint style, jint size, jint glyphCode, jint rotation,
    jint measuringMode, jint renderingMode, jfloat clearTypeLevel, jfloat enhancedContrast, jfloat gamma, jint pixelGeometry) {
    // variables cleared by FREE macro
    IDWriteFactory* factory = NULL;
    IDWriteGdiInterop* interop = NULL;
    IDWriteFont* font = NULL;
    IDWriteFontFace* face = NULL;
    IDWriteRenderingParams* defaultParams = NULL;
    IDWriteRenderingParams* params = NULL;
    IDWriteBitmapRenderTarget* target = NULL;

    LOGFONTW lf;
    memset(&lf, 0, sizeof(LOGFONTW));
    lf.lfWeight = (style & 1) ? FW_BOLD : FW_NORMAL;
    lf.lfItalic = (style & 2) ? TRUE : FALSE;

    int nameLen = env->GetStringLength(fontFamily);
    if (nameLen >= (sizeof(lf.lfFaceName) / sizeof(lf.lfFaceName[0]))) {
        FREE_AND_RETURN
    }
    env->GetStringRegion(fontFamily, 0, nameLen, lf.lfFaceName);
    lf.lfFaceName[nameLen] = '\0';

    if (fDWriteCreateFactory == NULL) {
        FREE_AND_RETURN
    }
    HRESULT hr = (*fDWriteCreateFactory)(DWRITE_FACTORY_TYPE_SHARED,
                                         __uuidof(IDWriteFactory),
                                         reinterpret_cast<IUnknown**>(&factory));
    if (FAILED(hr)) {
        FREE_AND_RETURN
    }
    hr = factory->GetGdiInterop(&interop);
    if (FAILED(hr)) {
        FREE_AND_RETURN
    }
    hr = interop->CreateFontFromLOGFONT(&lf, &font);
    if (FAILED(hr)) {
        FREE_AND_RETURN
    }
    hr = font->CreateFontFace(&face);
    if (FAILED(hr)) {
        FREE_AND_RETURN
    }
    hr = factory->CreateRenderingParams(&defaultParams);
    if (FAILED(hr)) {
        FREE_AND_RETURN
    }
    hr = factory->CreateCustomRenderingParams(
        gamma > 0 && gamma <= 256                  ? gamma                                : defaultParams->GetGamma(),
        enhancedContrast >= 0                      ? enhancedContrast                     : defaultParams->GetEnhancedContrast(),
        clearTypeLevel >= 0 && clearTypeLevel <= 1 ? clearTypeLevel                       : defaultParams->GetClearTypeLevel(),
        pixelGeometry >= 0 && pixelGeometry <= 2   ? (DWRITE_PIXEL_GEOMETRY)pixelGeometry : defaultParams->GetPixelGeometry(),
        renderingMode >= 0 && renderingMode <= 6   ? (DWRITE_RENDERING_MODE)renderingMode : defaultParams->GetRenderingMode(),
        &params);
    if (FAILED(hr)) {
        FREE_AND_RETURN
    }

    UINT16 indices[] = {(UINT16)glyphCode};
    FLOAT advances[] = {0};
    DWRITE_GLYPH_OFFSET offsets[] = {{0, 0}};
    DWRITE_GLYPH_RUN glyphRun;
    glyphRun.fontFace = face;
    glyphRun.fontEmSize = (FLOAT)size;
    glyphRun.glyphCount = 1;
    glyphRun.glyphIndices = indices;
    glyphRun.glyphAdvances = advances;
    glyphRun.glyphOffsets = offsets;
    glyphRun.isSideways = FALSE;
    glyphRun.bidiLevel = 0;

    DWRITE_FONT_METRICS fontMetrics;
    face->GetMetrics(&fontMetrics);
    FLOAT pxPerDU = ((FLOAT)size) / fontMetrics.designUnitsPerEm;

    DWRITE_GLYPH_METRICS metrics[1];
    hr = face->GetDesignGlyphMetrics(indices, 1, metrics, FALSE);
    if (FAILED(hr)) {
        FREE_AND_RETURN
    }

    // trying to derive required bitmap size from glyph metrics (adding several spare pixels on each border)
    // if that will fail, we'll perform a second attempt based on the output of DrawGlyphRun
    int width = (int)((metrics[0].advanceWidth - metrics[0].leftSideBearing - metrics[0].rightSideBearing) * pxPerDU) + 10;
    int height = (int)((metrics[0].advanceHeight - metrics[0].topSideBearing - metrics[0].bottomSideBearing) * pxPerDU) + 10;
    int x = (int)(-metrics[0].leftSideBearing * pxPerDU) + 5;
    int y = (int)((metrics[0].verticalOriginY - metrics[0].topSideBearing) * pxPerDU) + 5;
    float advance = (float)((int)(metrics[0].advanceWidth * pxPerDU + 0.5));
    RECT bbRect;

    DWRITE_MATRIX mx;
    switch (rotation) {
        case 0: mx.m11 = mx.m22 = 1; mx.m12 = mx.m21 = mx.dx = mx.dy = 0; break;
        case 1: mx.m11 = mx.m22 = 0; mx.m12 = -1; mx.m21 = 1; mx.dx = 0; mx.dy = (float)width; break;
        case 2: mx.m11 = mx.m22 = -1; mx.m12 = mx.m21 = 0; mx.dx = (float)width; mx.dy = (float)height; break;
        case 3: mx.m11 = mx.m22 = 0; mx.m12 = 1; mx.m21 = -1; mx.dx = (float)height; mx.dy = 0;
    }
    if (rotation == 1 || rotation == 3) {
        int tmp = width;
        width = height;
        height = tmp;
    }
    float xTransformed = mx.m11 * x - mx.m12 * y + mx.dx;
    float yTransformed = -mx.m21 * x + mx.m22 * y + mx.dy;

    for (int attempt = 0; attempt < 2 && target == NULL; attempt++) {
        hr = interop->CreateBitmapRenderTarget(NULL, width, height, &target);
        if (FAILED(hr)) {
            FREE_AND_RETURN
        }
        hr = target->SetCurrentTransform(&mx);
        if (FAILED(hr)) {
            FREE_AND_RETURN
        }
        hr = target->DrawGlyphRun((FLOAT)x,
                                  (FLOAT)y,
                                  measuringMode >= 0 && measuringMode <= 2 ? (DWRITE_MEASURING_MODE)measuringMode : DWRITE_MEASURING_MODE_NATURAL,
                                  &glyphRun,
                                  params,
                                  RGB(255,255,255),
                                  &bbRect);
        if (FAILED(hr) || bbRect.left > bbRect.right || bbRect.top > bbRect.bottom
            || attempt > 0 && (bbRect.left < 0 || bbRect.top < 0 || bbRect.right > width || bbRect.bottom > height)) {
            FREE_AND_RETURN
        }
        if (bbRect.left < 0 || bbRect.top < 0 || bbRect.right > width || bbRect.bottom > height) {
            target->Release();
            target = NULL;
            if (bbRect.right > width) width = bbRect.right;
            if (bbRect.bottom > height) height = bbRect.bottom;
            if (bbRect.left < 0) {
                width -= bbRect.left;
                switch (rotation) {
                    case 0: x -= bbRect.left; break;
                    case 1: y -= bbRect.left; break;
                    case 2: x += bbRect.left; break;
                    case 3: y += bbRect.left;
                }
            }
            if (bbRect.top < 0) {
                height -= bbRect.top;
                switch (rotation) {
                    case 0: y -= bbRect.top; break;
                    case 1: x += bbRect.top; break;
                    case 2: y += bbRect.top; break;
                    case 3: x -= bbRect.top;
                }
            }
        }
    }

    HDC glyphDC = target->GetMemoryDC();
    HGDIOBJ glyphBitmap = GetCurrentObject(glyphDC, OBJ_BITMAP);
    if (glyphBitmap == NULL) {
        FREE_AND_RETURN
    }
    DIBSECTION dibSection;
    if (GetObject(glyphBitmap, sizeof(DIBSECTION), &dibSection) == 0) {
        FREE_AND_RETURN
    }

    int glyphWidth = bbRect.right - bbRect.left;
    int glyphHeight = bbRect.bottom - bbRect.top;
    int glyphBytesWidth = glyphWidth * 3;
    GlyphInfo* glyphInfo = (GlyphInfo*)SAFE_SIZE_STRUCT_ALLOC(malloc, sizeof(GlyphInfo), glyphBytesWidth, glyphHeight);
    if (glyphInfo == NULL) {
        FREE_AND_RETURN
    }
    glyphInfo->managed = UNMANAGED_GLYPH;
    glyphInfo->cellInfo = NULL;
    glyphInfo->image = (unsigned char*)glyphInfo+sizeof(GlyphInfo);
    glyphInfo->rowBytes = glyphBytesWidth;
    glyphInfo->width = glyphWidth;
    glyphInfo->height = glyphHeight;
    glyphInfo->advanceX = rotation == 0 ? advance : rotation == 2 ? -advance : 0;
    glyphInfo->advanceY = rotation == 3 ? advance : rotation == 1 ? -advance : 0;
    glyphInfo->topLeftX = bbRect.left - xTransformed;
    glyphInfo->topLeftY = bbRect.top - yTransformed;

    int srcRowBytes = width * 4;
    unsigned char* srcPtr = (unsigned char*) dibSection.dsBm.bmBits + srcRowBytes * bbRect.top;
    unsigned char* destPtr = glyphInfo->image;
    for (int y = 0; y < glyphHeight; y++) {
        srcPtr += bbRect.left * 4;
        for (int x = 0; x < glyphWidth; x++) {
            // converting from BGRA to RGB
            unsigned char b = *srcPtr++;
            unsigned char g = *srcPtr++;
            unsigned char r = *srcPtr++;
            srcPtr++;
            *destPtr++ = r;
            *destPtr++ = g;
            *destPtr++ = b;
        }
        srcPtr += (width - bbRect.right) * 4;
    }

    FREE
    return ptr_to_jlong(glyphInfo);
}

} // extern "C"