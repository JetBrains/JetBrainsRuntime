#version 450
#extension GL_GOOGLE_include_directive: require
#extension GL_EXT_debug_printf : enable
#include "gradient_common.glsl"

DEFAULT_PUSH_CONSTANTS();
GENERIC_INOUT();

layout(std140, set = 1, binding = 0) uniform RadialGradientUBO {
    vec3 m0;
    vec3 m1;
    vec3 precalc;
    GradientStops stops;
} u_GradientParams;

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

   /* debugPrintfEXT("Frag coords: %f %f\n", gl_FragCoord.x, gl_FragCoord.y);

    if (gl_FragCoord.x == 40.5 && gl_FragCoord.y == 0.5) {
                debugPrintfEXT("RadialGradientUBO:\n");
                debugPrintfEXT("  m0: vec3(%.6f, %.6f, %.6f)\n", u_GradientParams.m0.x, u_GradientParams.m0.y, u_GradientParams.m0.z);
                debugPrintfEXT("  m1: vec3(%.6f, %.6f, %.6f)\n", u_GradientParams.m1.x, u_GradientParams.m1.y, u_GradientParams.m1.z);
                debugPrintfEXT("  pc: vec3(%.6f, %.6f, %.6f)\n", u_GradientParams.precalc.x, u_GradientParams.precalc.y, u_GradientParams.precalc.z);
                for (int i = 0; i < GRADIENT_MAX_FRACTIONS; i++) {
                    debugPrintfEXT("  stops.fractions[%d]: %.6f\n", i, u_GradientParams.stops.fractions[i]);
                    debugPrintfEXT("  stops.colors[%d]: vec4(%.6f, %.6f, %.6f, %.6f)\n",
                        i,
                        u_GradientParams.stops.colors[i].x,
                        u_GradientParams.stops.colors[i].y,
                        u_GradientParams.stops.colors[i].z,
                        u_GradientParams.stops.colors[i].w);
                }
    }
    */

    float x = dot(vec3(in_Position, 1.0), u_GradientParams.m0);
    float y = dot(vec3(in_Position, 1.0), u_GradientParams.m1);
    float xfx = x - u_GradientParams.precalc.x;
    float t = (u_GradientParams.precalc.x * xfx + sqrt(xfx * xfx + y * y * u_GradientParams.precalc.y)) * u_GradientParams.precalc.z;
    OUTPUT(interpolateMultiGradient(t, u_GradientParams.stops));
}
