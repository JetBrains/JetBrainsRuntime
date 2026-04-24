#version 450
#extension GL_GOOGLE_include_directive: require
#extension GL_EXT_debug_printf : enable
#include "gradient_common.glsl"

DEFAULT_PUSH_CONSTANTS();
GENERIC_INOUT();

layout(std140, set = 1, binding = 0) uniform LinearGradientUBO {
    vec3 p;
    GradientStops stops;
} u_GradientParams;

void main() {

    
    if (gl_FragCoord.x == 40.5 && gl_FragCoord.y == 0.5) {
        debugPrintfEXT("LinearGradientUBO:\n");
        debugPrintfEXT("  p: vec3(%.6f, %.6f, %.6f)\n", u_GradientParams.p.x, u_GradientParams.p.y, u_GradientParams.p.z);
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

    float t = dot(vec3(in_Position, 1.0), u_GradientParams.p);
    OUTPUT(interpolateMultiGradient(t, u_GradientParams.stops));
}
