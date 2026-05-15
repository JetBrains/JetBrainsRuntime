#version 450
#extension GL_GOOGLE_include_directive: require

#define GRADIENT_MAX_FRACTIONS 7
#include "gradient_common_push.glsl"

struct VKRadialGradientPaintConstants {
    vec3 m0; float precalc_x;
    vec3 m1; float precalc_y;
    float precalc_z;
    GradientStops stops;
};
PUSH_CONSTANTS(VKRadialGradientPaintConstants);

GENERIC_INOUT();

/*
 * Explanation duplicated from D3DShaderGen.c; The same algorithm is used in MTLPaints.m.
 *
 * To simplify the code and to make it easier to upload a number of
 * uniform values at once, we pack a bunch of scalar (float) values
 * into float3 values below.  Here's how the values are related:
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
 *   precalc.y = 1.0 - (focusX * focusX)
 *   precalc.z = 1.0 / precalc.y
 */

void main() {
    float x = dot(vec3(in_Position, 1.0), push.m0);
    float y = dot(vec3(in_Position, 1.0), push.m1);
    float xfx = x - push.precalc_x;
    float t = (push.precalc_x * xfx + sqrt(xfx * xfx + y * y * push.precalc_y)) * push.precalc_z;
    OUTPUT(convertAlpha(interpolateMultiGradient(t, push.stops)));
}
