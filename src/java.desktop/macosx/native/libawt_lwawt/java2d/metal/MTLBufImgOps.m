/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

#ifndef HEADLESS

#include <jlong.h>

#include "MTLBufImgOps.h"
#include "MTLContext.h"
#include "MTLRenderQueue.h"
#include "MTLSurfaceDataBase.h"
#include "GraphicsPrimitiveMgr.h"

/** Evaluates to true if the given bit is set on the local flags variable. */
#define IS_SET(flagbit) \
    (((flags) & (flagbit)) != 0)

/**************************** ConvolveOp support ****************************/

/**
 * The ConvolveOp shader is fairly straightforward.  For each texel in
 * the source texture, the shader samples the MxN texels in the surrounding
 * area, multiplies each by its corresponding kernel value, and then sums
 * them all together to produce a single color result.  Finally, the
 * resulting value is multiplied by the current OpenGL color, which contains
 * the extra alpha value.
 *
 * Note that this shader source code includes some "holes" marked by "%s".
 * This allows us to build different shader programs (e.g. one for
 * 3x3, one for 5x5, and so on) simply by filling in these "holes" with
 * a call to sprintf().  See the MTLBufImgOps_CreateConvolveProgram() method
 * for more details.
 *
 * REMIND: Currently this shader (and the supporting code in the
 *         EnableConvolveOp() method) only supports 3x3 and 5x5 filters.
 *         Early shader-level hardware did not support non-constant sized
 *         arrays but modern hardware should support them (although I
 *         don't know of any simple way to find out, other than to compile
 *         the shader at runtime and see if the drivers complain).
 */
static const char *convolveShaderSource =
    // maximum size supported by this shader
    "const int MAX_KERNEL_SIZE = %s;"
    // image to be convolved
    "uniform sampler%s baseImage;"
    // image edge limits:
    //   imgEdge.xy = imgMin.xy (anything < will be treated as edge case)
    //   imgEdge.zw = imgMax.xy (anything > will be treated as edge case)
    "uniform vec4 imgEdge;"
    // value for each location in the convolution kernel:
    //   kernelVals[i].x = offsetX[i]
    //   kernelVals[i].y = offsetY[i]
    //   kernelVals[i].z = kernel[i]
    "uniform vec3 kernelVals[MAX_KERNEL_SIZE];"
    ""
    "void main(void)"
    "{"
    "    int i;"
    "    vec4 sum;"
    ""
    "    if (any(lessThan(gl_TexCoord[0].st, imgEdge.xy)) ||"
    "        any(greaterThan(gl_TexCoord[0].st, imgEdge.zw)))"
    "    {"
             // (placeholder for edge condition code)
    "        %s"
    "    } else {"
    "        sum = vec4(0.0);"
    "        for (i = 0; i < MAX_KERNEL_SIZE; i++) {"
    "            sum +="
    "                kernelVals[i].z *"
    "                texture%s(baseImage,"
    "                          gl_TexCoord[0].st + kernelVals[i].xy);"
    "        }"
    "    }"
    ""
         // modulate with gl_Color in order to apply extra alpha
    "    gl_FragColor = sum * gl_Color;"
    "}";

/**
 * Flags that can be bitwise-or'ed together to control how the shader
 * source code is generated.
 */
#define CONVOLVE_RECT            (1 << 0)
#define CONVOLVE_EDGE_ZERO_FILL  (1 << 1)
#define CONVOLVE_5X5             (1 << 2)

/**
 * The handles to the ConvolveOp fragment program objects.  The index to
 * the array should be a bitwise-or'ing of the CONVOLVE_* flags defined
 * above.  Note that most applications will likely need to initialize one
 * or two of these elements, so the array is usually sparsely populated.
 */
static GLhandleARB convolvePrograms[8];

/**
 * The maximum kernel size supported by the ConvolveOp shader.
 */
#define MAX_KERNEL_SIZE 25

/**
 * Compiles and links the ConvolveOp shader program.  If successful, this
 * function returns a handle to the newly created shader program; otherwise
 * returns 0.
 */
static GLhandleARB
MTLBufImgOps_CreateConvolveProgram(jint flags)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_CreateConvolveProgram");
    return NULL;
}

void
MTLBufImgOps_EnableConvolveOp(MTLContext *mtlc, jlong pSrcOps,
                              jboolean edgeZeroFill,
                              jint kernelWidth, jint kernelHeight,
                              unsigned char *kernel)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_EnableConvolveOp");
}

void
MTLBufImgOps_DisableConvolveOp(MTLContext *mtlc)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_EnableConvolveOp");
    J2dTraceLn(J2D_TRACE_INFO, "MTLBufImgOps_DisableConvolveOp");
}

/**************************** RescaleOp support *****************************/

/**
 * The RescaleOp shader is one of the simplest possible.  Each fragment
 * from the source image is multiplied by the user's scale factor and added
 * to the user's offset value (these are component-wise operations).
 * Finally, the resulting value is multiplied by the current OpenGL color,
 * which contains the extra alpha value.
 *
 * The RescaleOp spec says that the operation is performed regardless of
 * whether the source data is premultiplied or non-premultiplied.  This is
 * a problem for the OpenGL pipeline in that a non-premultiplied
 * BufferedImage will have already been converted into premultiplied
 * when uploaded to an OpenGL texture.  Therefore, we have a special mode
 * called RESCALE_NON_PREMULT (used only for source images that were
 * originally non-premultiplied) that un-premultiplies the source color
 * prior to the rescale operation, then re-premultiplies the resulting
 * color before returning from the fragment shader.
 *
 * Note that this shader source code includes some "holes" marked by "%s".
 * This allows us to build different shader programs (e.g. one for
 * GL_TEXTURE_2D targets, one for GL_TEXTURE_RECTANGLE_ARB targets, and so on)
 * simply by filling in these "holes" with a call to sprintf().  See the
 * MTLBufImgOps_CreateRescaleProgram() method for more details.
 */
static const char *rescaleShaderSource =
    // image to be rescaled
    "uniform sampler%s baseImage;"
    // vector containing scale factors
    "uniform vec4 scaleFactors;"
    // vector containing offsets
    "uniform vec4 offsets;"
    ""
    "void main(void)"
    "{"
    "    vec4 srcColor = texture%s(baseImage, gl_TexCoord[0].st);"
         // (placeholder for un-premult code)
    "    %s"
         // rescale source value
    "    vec4 result = (srcColor * scaleFactors) + offsets;"
         // (placeholder for re-premult code)
    "    %s"
         // modulate with gl_Color in order to apply extra alpha
    "    gl_FragColor = result * gl_Color;"
    "}";

/**
 * Flags that can be bitwise-or'ed together to control how the shader
 * source code is generated.
 */
#define RESCALE_RECT        (1 << 0)
#define RESCALE_NON_PREMULT (1 << 1)

/**
 * The handles to the RescaleOp fragment program objects.  The index to
 * the array should be a bitwise-or'ing of the RESCALE_* flags defined
 * above.  Note that most applications will likely need to initialize one
 * or two of these elements, so the array is usually sparsely populated.
 */
static GLhandleARB rescalePrograms[4];

/**
 * Compiles and links the RescaleOp shader program.  If successful, this
 * function returns a handle to the newly created shader program; otherwise
 * returns 0.
 */
static GLhandleARB
MTLBufImgOps_CreateRescaleProgram(jint flags)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_CreateRescaleProgram");
    return NULL;
}

void
MTLBufImgOps_EnableRescaleOp(MTLContext *mtlc, jlong pSrcOps,
                             jboolean nonPremult,
                             unsigned char *scaleFactors,
                             unsigned char *offsets)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_EnableRescaleOp");
}

void
MTLBufImgOps_DisableRescaleOp(MTLContext *mtlc)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_DisableRescaleOp");
    J2dTraceLn(J2D_TRACE_INFO, "MTLBufImgOps_DisableRescaleOp");
    RETURN_IF_NULL(mtlc);
}

/**************************** LookupOp support ******************************/

/**
 * The LookupOp shader takes a fragment color (from the source texture) as
 * input, subtracts the optional user offset value, and then uses the
 * resulting value to index into the lookup table texture to provide
 * a new color result.  Finally, the resulting value is multiplied by
 * the current OpenGL color, which contains the extra alpha value.
 *
 * The lookup step requires 3 texture accesses (or 4, when alpha is included),
 * which is somewhat unfortunate because it's not ideal from a performance
 * standpoint, but that sort of thing is getting faster with newer hardware.
 * In the 3-band case, we could consider using a three-dimensional texture
 * and performing the lookup with a single texture access step.  We already
 * use this approach in the LCD text shader, and it works well, but for the
 * purposes of this LookupOp shader, it's probably overkill.  Also, there's
 * a difference in that the LCD text shader only needs to populate the 3D LUT
 * once, but here we would need to populate it on every invocation, which
 * would likely be a waste of VRAM and CPU/GPU cycles.
 *
 * The LUT texture is currently hardcoded as 4 rows/bands, each containing
 * 256 elements.  This means that we currently only support user-provided
 * tables with no more than 256 elements in each band (this is checked at
 * at the Java level).  If the user provides a table with less than 256
 * elements per band, our shader will still work fine, but if elements are
 * accessed with an index >= the size of the LUT, then the shader will simply
 * produce undefined values.  Typically the user would provide an offset
 * value that would prevent this from happening, but it's worth pointing out
 * this fact because the software LookupOp implementation would usually
 * throw an ArrayIndexOutOfBoundsException in this scenario (although it is
 * not something demanded by the spec).
 *
 * The LookupOp spec says that the operation is performed regardless of
 * whether the source data is premultiplied or non-premultiplied.  This is
 * a problem for the OpenGL pipeline in that a non-premultiplied
 * BufferedImage will have already been converted into premultiplied
 * when uploaded to an OpenGL texture.  Therefore, we have a special mode
 * called LOOKUP_NON_PREMULT (used only for source images that were
 * originally non-premultiplied) that un-premultiplies the source color
 * prior to the lookup operation, then re-premultiplies the resulting
 * color before returning from the fragment shader.
 *
 * Note that this shader source code includes some "holes" marked by "%s".
 * This allows us to build different shader programs (e.g. one for
 * GL_TEXTURE_2D targets, one for GL_TEXTURE_RECTANGLE_ARB targets, and so on)
 * simply by filling in these "holes" with a call to sprintf().  See the
 * MTLBufImgOps_CreateLookupProgram() method for more details.
 */
static const char *lookupShaderSource =
    // source image (bound to texture unit 0)
    "uniform sampler%s baseImage;"
    // lookup table (bound to texture unit 1)
    "uniform sampler2D lookupTable;"
    // offset subtracted from source index prior to lookup step
    "uniform vec4 offset;"
    ""
    "void main(void)"
    "{"
    "    vec4 srcColor = texture%s(baseImage, gl_TexCoord[0].st);"
         // (placeholder for un-premult code)
    "    %s"
         // subtract offset from original index
    "    vec4 srcIndex = srcColor - offset;"
         // use source value as input to lookup table (note that
         // "v" texcoords are hardcoded to hit texel centers of
         // each row/band in texture)
    "    vec4 result;"
    "    result.r = texture2D(lookupTable, vec2(srcIndex.r, 0.125)).r;"
    "    result.g = texture2D(lookupTable, vec2(srcIndex.g, 0.375)).r;"
    "    result.b = texture2D(lookupTable, vec2(srcIndex.b, 0.625)).r;"
         // (placeholder for alpha store code)
    "    %s"
         // (placeholder for re-premult code)
    "    %s"
         // modulate with gl_Color in order to apply extra alpha
    "    gl_FragColor = result * gl_Color;"
    "}";

/**
 * Flags that can be bitwise-or'ed together to control how the shader
 * source code is generated.
 */
#define LOOKUP_RECT          (1 << 0)
#define LOOKUP_USE_SRC_ALPHA (1 << 1)
#define LOOKUP_NON_PREMULT   (1 << 2)

/**
 * The handles to the LookupOp fragment program objects.  The index to
 * the array should be a bitwise-or'ing of the LOOKUP_* flags defined
 * above.  Note that most applications will likely need to initialize one
 * or two of these elements, so the array is usually sparsely populated.
 */
static GLhandleARB lookupPrograms[8];

/**
 * The handle to the lookup table texture object used by the shader.
 */
static GLuint lutTextureID = 0;

/**
 * Compiles and links the LookupOp shader program.  If successful, this
 * function returns a handle to the newly created shader program; otherwise
 * returns 0.
 */
static GLhandleARB
MTLBufImgOps_CreateLookupProgram(jint flags)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_CreateLookupProgram");

    return NULL;
}

void
MTLBufImgOps_EnableLookupOp(MTLContext *mtlc, jlong pSrcOps,
                            jboolean nonPremult, jboolean shortData,
                            jint numBands, jint bandLength, jint offset,
                            void *tableValues)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_EnableLookupOp");
}

void
MTLBufImgOps_DisableLookupOp(MTLContext *mtlc)
{
    //TODO
    J2dTracePrimitive("MTLBufImgOps_DisableLookupOp");
    J2dTraceLn(J2D_TRACE_INFO, "MTLBufImgOps_DisableLookupOp");
}

#endif /* !HEADLESS */
