#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

#define SHADER_VARIANT_GRADIENT_CLAMP 0
#define SHADER_VARIANT_GRADIENT_CYCLE 1

struct VKGradientPaintConstants {
    vec4 c0, c1;
    vec3 p;
};
PUSH_CONSTANTS(VKGradientPaintConstants);
GENERIC_INOUT();

void main() {
    float t = dot(vec3(in_Position, 1.0), push.p);
    t = const_ShaderVariant == SHADER_VARIANT_GRADIENT_CYCLE ?
        abs(mod(t + 1.0, 2.0) - 1.0) : // Cycle
        clamp(t, 0.0, 1.0);            // Clamp
    OUTPUT(convertAlpha(mix(push.c0, push.c1, t)));
}
