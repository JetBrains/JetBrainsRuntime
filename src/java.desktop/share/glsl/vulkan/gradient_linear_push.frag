#version 450
#extension GL_GOOGLE_include_directive: require

#define GRADIENT_MAX_FRACTIONS 10
#include "gradient_common_push.glsl"

struct VKLinearGradientPaintConstants {
    vec3 p;
    GradientStops stops;
};
PUSH_CONSTANTS(VKLinearGradientPaintConstants);

GENERIC_INOUT();

void main() {
    float t = dot(vec3(in_Position, 1.0), push.p);
    OUTPUT(convertAlpha(interpolateMultiGradient(t, push.stops)));
}
