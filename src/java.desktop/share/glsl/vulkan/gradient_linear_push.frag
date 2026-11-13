#version 450
#extension GL_GOOGLE_include_directive: require

#define GRADIENT_MAX_FRACTIONS 10
#include "gradient_common_push.glsl"

struct VKLinearGradientPaintConstants {
    vec3 p;
    GradientStops stops;
};
PUSH_CONSTANTS(VKLinearGradientPaintConstants);

void main() {
    float t = dot(vec3(gl_FragCoord.xy, 1.0), push.p);
    outputColor(convertAlpha(interpolateMultiGradient(t, push.stops)));
}
