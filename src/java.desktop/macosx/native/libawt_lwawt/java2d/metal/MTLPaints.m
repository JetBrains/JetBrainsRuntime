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
#include <string.h>

#include "sun_java2d_SunGraphics2D.h"
#include "sun_java2d_pipe_BufferedPaints.h"

#include "MTLPaints.h"
#include "MTLContext.h"
#include "MTLRenderQueue.h"
#include "MTLSurfaceData.h"

void
MTLPaints_ResetPaint(MTLContext *mtlc)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_ResetPaint");
    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints_ResetPaint");
}

void
MTLPaints_SetColor(MTLContext *mtlc, jint pixel)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_SetColor");
    J2dTraceLn1(J2D_TRACE_INFO, "MTLPaints_SetColor: pixel=%08x", pixel);
}

/************************* GradientPaint support ****************************/

static GLuint gradientTexID = 0;

static void
MTLPaints_InitGradientTexture()
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_InitGradientTexture");
    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints_InitGradientTexture");
}

void
MTLPaints_SetGradientPaint(MTLContext *mtlc,
                           jboolean useMask, jboolean cyclic,
                           jdouble p0, jdouble p1, jdouble p3,
                           jint pixel1, jint pixel2)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_SetGradientPaint");
    J2dTraceLn(J2D_TRACE_INFO, "MTLPaints_SetGradientPaint");
}

/************************** TexturePaint support ****************************/

void
MTLPaints_SetTexturePaint(MTLContext *mtlc,
                          jboolean useMask,
                          jlong pSrcOps, jboolean filter,
                          jdouble xp0, jdouble xp1, jdouble xp3,
                          jdouble yp0, jdouble yp1, jdouble yp3)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_SetTexturePaint");
}

/****************** Shared MultipleGradientPaint support ********************/

/**
 * These constants are identical to those defined in the
 * MultipleGradientPaint.CycleMethod enum; they are copied here for
 * convenience (ideally we would pull them directly from the Java level,
 * but that entails more hassle than it is worth).
 */
#define CYCLE_NONE    0
#define CYCLE_REFLECT 1
#define CYCLE_REPEAT  2

/**
 * The following constants are flags that can be bitwise-or'ed together
 * to control how the MultipleGradientPaint shader source code is generated:
 *
 *   MULTI_CYCLE_METHOD
 *     Placeholder for the CycleMethod enum constant.
 *
 *   MULTI_LARGE
 *     If set, use the (slower) shader that supports a larger number of
 *     gradient colors; otherwise, use the optimized codepath.  See
 *     the MAX_FRACTIONS_SMALL/LARGE constants below for more details.
 *
 *   MULTI_USE_MASK
 *     If set, apply the alpha mask value from texture unit 0 to the
 *     final color result (only used in the MaskFill case).
 *
 *   MULTI_LINEAR_RGB
 *     If set, convert the linear RGB result back into the sRGB color space.
 */
#define MULTI_CYCLE_METHOD (3 << 0)
#define MULTI_LARGE        (1 << 2)
#define MULTI_USE_MASK     (1 << 3)
#define MULTI_LINEAR_RGB   (1 << 4)

/**
 * This value determines the size of the array of programs for each
 * MultipleGradientPaint type.  This value reflects the maximum value that
 * can be represented by performing a bitwise-or of all the MULTI_*
 * constants defined above.
 */
#define MAX_PROGRAMS 32

/** Evaluates to true if the given bit is set on the local flags variable. */
#define IS_SET(flagbit) \
    (((flags) & (flagbit)) != 0)

/** Composes the given parameters as flags into the given flags variable.*/
#define COMPOSE_FLAGS(flags, cycleMethod, large, useMask, linear) \
    do {                                                   \
        flags |= ((cycleMethod) & MULTI_CYCLE_METHOD);     \
        if (large)   flags |= MULTI_LARGE;                 \
        if (useMask) flags |= MULTI_USE_MASK;              \
        if (linear)  flags |= MULTI_LINEAR_RGB;            \
    } while (0)

/** Extracts the CycleMethod enum value from the given flags variable. */
#define EXTRACT_CYCLE_METHOD(flags) \
    ((flags) & MULTI_CYCLE_METHOD)

/**
 * The maximum number of gradient "stops" supported by the fragment shader
 * and related code.  When the MULTI_LARGE flag is set, we will use
 * MAX_FRACTIONS_LARGE; otherwise, we use MAX_FRACTIONS_SMALL.  By having
 * two separate values, we can have one highly optimized shader (SMALL) that
 * supports only a few fractions/colors, and then another, less optimal
 * shader that supports more stops.
 */
#define MAX_FRACTIONS sun_java2d_pipe_BufferedPaints_MULTI_MAX_FRACTIONS
#define MAX_FRACTIONS_LARGE MAX_FRACTIONS
#define MAX_FRACTIONS_SMALL 4

/**
 * The maximum number of gradient colors supported by all of the gradient
 * fragment shaders.  Note that this value must be a power of two, as it
 * determines the size of the 1D texture created below.  It also must be
 * greater than or equal to MAX_FRACTIONS (there is no strict requirement
 * that the two values be equal).
 */
#define MAX_COLORS 16

/**
 * The handle to the gradient color table texture object used by the shaders.
 */
static jint multiGradientTexID = 0;

/**
 * This is essentially a template of the shader source code that can be used
 * for either LinearGradientPaint or RadialGradientPaint.  It includes the
 * structure and some variables that are common to each; the remaining
 * code snippets (for CycleMethod, ColorSpaceType, and mask modulation)
 * are filled in prior to compiling the shader at runtime depending on the
 * paint parameters.  See MTLPaints_CreateMultiGradProgram() for more details.
 */
static const char *multiGradientShaderSource =
    // gradient texture size (in texels)
    "const int TEXTURE_SIZE = %d;"
    // maximum number of fractions/colors supported by this shader
    "const int MAX_FRACTIONS = %d;"
    // size of a single texel
    "const float FULL_TEXEL = (1.0 / float(TEXTURE_SIZE));"
    // size of half of a single texel
    "const float HALF_TEXEL = (FULL_TEXEL / 2.0);"
    // texture containing the gradient colors
    "uniform sampler1D colors;"
    // array of gradient stops/fractions
    "uniform float fractions[MAX_FRACTIONS];"
    // array of scale factors (one for each interval)
    "uniform float scaleFactors[MAX_FRACTIONS-1];"
    // (placeholder for mask variable)
    "%s"
    // (placeholder for Linear/RadialGP-specific variables)
    "%s"
    ""
    "void main(void)"
    "{"
    "    float dist;"
         // (placeholder for Linear/RadialGradientPaint-specific code)
    "    %s"
    ""
    "    float tc;"
         // (placeholder for CycleMethod-specific code)
    "    %s"
    ""
         // calculate interpolated color
    "    vec4 result = texture1D(colors, tc);"
    ""
         // (placeholder for ColorSpace conversion code)
    "    %s"
    ""
         // (placeholder for mask modulation code)
    "    %s"
    ""
         // modulate with gl_Color in order to apply extra alpha
    "    gl_FragColor = result * gl_Color;"
    "}";

/**
 * This code takes a "dist" value as input (as calculated earlier by the
 * LGP/RGP-specific code) in the range [0,1] and produces a texture
 * coordinate value "tc" that represents the position of the chosen color
 * in the one-dimensional gradient texture (also in the range [0,1]).
 *
 * One naive way to implement this would be to iterate through the fractions
 * to figure out in which interval "dist" falls, and then compute the
 * relative distance between the two nearest stops.  This approach would
 * require an "if" check on every iteration, and it is best to avoid
 * conditionals in fragment shaders for performance reasons.  Also, one might
 * be tempted to use a break statement to jump out of the loop once the
 * interval was found, but break statements (and non-constant loop bounds)
 * are not natively available on most graphics hardware today, so that is
 * a non-starter.
 *
 * The more optimal approach used here avoids these issues entirely by using
 * an accumulation function that is equivalent to the process described above.
 * The scaleFactors array is pre-initialized at enable time as follows:
 *     scaleFactors[i] = 1.0 / (fractions[i+1] - fractions[i]);
 *
 * For each iteration, we subtract fractions[i] from dist and then multiply
 * that value by scaleFactors[i].  If we are within the target interval,
 * this value will be a fraction in the range [0,1] indicating the relative
 * distance between fraction[i] and fraction[i+1].  If we are below the
 * target interval, this value will be negative, so we clamp it to zero
 * to avoid accumulating any value.  If we are above the target interval,
 * the value will be greater than one, so we clamp it to one.  Upon exiting
 * the loop, we will have accumulated zero or more 1.0's and a single
 * fractional value.  This accumulated value tells us the position of the
 * fragment color in the one-dimensional gradient texture, i.e., the
 * texcoord called "tc".
 */
static const char *texCoordCalcCode =
    "int i;"
    "float relFraction = 0.0;"
    "for (i = 0; i < MAX_FRACTIONS-1; i++) {"
    "    relFraction +="
    "        clamp((dist - fractions[i]) * scaleFactors[i], 0.0, 1.0);"
    "}"
    // we offset by half a texel so that we find the linearly interpolated
    // color between the two texel centers of interest
    "tc = HALF_TEXEL + (FULL_TEXEL * relFraction);";

/** Code for NO_CYCLE that gets plugged into the CycleMethod placeholder. */
static const char *noCycleCode =
    "if (dist <= 0.0) {"
    "    tc = 0.0;"
    "} else if (dist >= 1.0) {"
    "    tc = 1.0;"
    "} else {"
         // (placeholder for texcoord calculation)
    "    %s"
    "}";

/** Code for REFLECT that gets plugged into the CycleMethod placeholder. */
static const char *reflectCode =
    "dist = 1.0 - (abs(fract(dist * 0.5) - 0.5) * 2.0);"
    // (placeholder for texcoord calculation)
    "%s";

/** Code for REPEAT that gets plugged into the CycleMethod placeholder. */
static const char *repeatCode =
    "dist = fract(dist);"
    // (placeholder for texcoord calculation)
    "%s";

static void
MTLPaints_InitMultiGradientTexture()
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_InitMultiGradientTexture");
}

/**
 * Compiles and links the MultipleGradientPaint shader program.  If
 * successful, this function returns a handle to the newly created
 * shader program; otherwise returns 0.
 */
static GLhandleARB
MTLPaints_CreateMultiGradProgram(jint flags,
                                 char *paintVars, char *distCode)
{

    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_CreateMultiGradProgram");
    return NULL;
}

/**
 * Called from the MTLPaints_SetLinear/RadialGradientPaint() methods
 * in order to setup the fraction/color values that are common to both.
 */
static void
MTLPaints_SetMultiGradientPaint(GLhandleARB multiGradProgram,
                                jint numStops,
                                void *pFractions, void *pPixels)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_SetMultiGradientPaint");

}

/********************** LinearGradientPaint support *************************/

/**
 * The handles to the LinearGradientPaint fragment program objects.  The
 * index to the array should be a bitwise-or'ing of the MULTI_* flags defined
 * above.  Note that most applications will likely need to initialize one
 * or two of these elements, so the array is usually sparsely populated.
 */
static GLhandleARB linearGradPrograms[MAX_PROGRAMS];

/**
 * Compiles and links the LinearGradientPaint shader program.  If successful,
 * this function returns a handle to the newly created shader program;
 * otherwise returns 0.
 */
static GLhandleARB
MTLPaints_CreateLinearGradProgram(jint flags)
{
    char *paintVars;
    char *distCode;

    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLPaints_CreateLinearGradProgram",
                flags);

    /*
     * To simplify the code and to make it easier to upload a number of
     * uniform values at once, we pack a bunch of scalar (float) values
     * into vec3 values below.  Here's how the values are related:
     *
     *   params.x = p0
     *   params.y = p1
     *   params.z = p3
     *
     *   yoff = dstOps->yOffset + dstOps->height
     */
    paintVars =
        "uniform vec3 params;"
        "uniform float yoff;";
    distCode =
        // note that gl_FragCoord is in window space relative to the
        // lower-left corner, so we have to flip the y-coordinate here
        "vec3 fragCoord = vec3(gl_FragCoord.x, yoff-gl_FragCoord.y, 1.0);"
        "dist = dot(params, fragCoord);";

    return MTLPaints_CreateMultiGradProgram(flags, paintVars, distCode);
}

void
MTLPaints_SetLinearGradientPaint(MTLContext *mtlc, BMTLSDOps *dstOps,
                                 jboolean useMask, jboolean linear,
                                 jint cycleMethod, jint numStops,
                                 jfloat p0, jfloat p1, jfloat p3,
                                 void *fractions, void *pixels)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_SetMultiGradientPaint");
}

/********************** RadialGradientPaint support *************************/

/**
 * The handles to the RadialGradientPaint fragment program objects.  The
 * index to the array should be a bitwise-or'ing of the MULTI_* flags defined
 * above.  Note that most applications will likely need to initialize one
 * or two of these elements, so the array is usually sparsely populated.
 */
static GLhandleARB radialGradPrograms[MAX_PROGRAMS];

/**
 * Compiles and links the RadialGradientPaint shader program.  If successful,
 * this function returns a handle to the newly created shader program;
 * otherwise returns 0.
 */
static GLhandleARB
MTLPaints_CreateRadialGradProgram(jint flags)
{
    char *paintVars;
    char *distCode;

    J2dTraceLn1(J2D_TRACE_INFO,
                "MTLPaints_CreateRadialGradProgram",
                flags);

    /*
     * To simplify the code and to make it easier to upload a number of
     * uniform values at once, we pack a bunch of scalar (float) values
     * into vec3 and vec4 values below.  Here's how the values are related:
     *
     *   m0.x = m00
     *   m0.y = m01
     *   m0.z = m02
     *
     *   m1.x = m10
     *   m1.y = m11
     *   m1.z = m12
     *
     *   precalc.x = focusX
     *   precalc.y = yoff = dstOps->yOffset + dstOps->height
     *   precalc.z = 1.0 - (focusX * focusX)
     *   precalc.w = 1.0 / precalc.z
     */
    paintVars =
        "uniform vec3 m0;"
        "uniform vec3 m1;"
        "uniform vec4 precalc;";

    /*
     * The following code is derived from Daniel Rice's whitepaper on
     * radial gradient performance (attached to the bug report for 6521533).
     * Refer to that document as well as the setup code in the Java-level
     * BufferedPaints.setRadialGradientPaint() method for more details.
     */
    distCode =
        // note that gl_FragCoord is in window space relative to the
        // lower-left corner, so we have to flip the y-coordinate here
        "vec3 fragCoord ="
        "    vec3(gl_FragCoord.x, precalc.y - gl_FragCoord.y, 1.0);"
        "float x = dot(fragCoord, m0);"
        "float y = dot(fragCoord, m1);"
        "float xfx = x - precalc.x;"
        "dist = (precalc.x*xfx + sqrt(xfx*xfx + y*y*precalc.z))*precalc.w;";

    return MTLPaints_CreateMultiGradProgram(flags, paintVars, distCode);
}

void
MTLPaints_SetRadialGradientPaint(MTLContext *mtlc, BMTLSDOps *dstOps,
                                 jboolean useMask, jboolean linear,
                                 jint cycleMethod, jint numStops,
                                 jfloat m00, jfloat m01, jfloat m02,
                                 jfloat m10, jfloat m11, jfloat m12,
                                 jfloat focusX,
                                 void *fractions, void *pixels)
{
    //TODO
    J2dTraceNotImplPrimitive("MTLPaints_SetRadialGradientPaint");
}

#endif /* !HEADLESS */
