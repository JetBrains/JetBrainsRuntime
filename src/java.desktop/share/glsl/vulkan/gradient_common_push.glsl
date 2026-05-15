#include "common.glsl"

#define GRADIENT_CYCLE_METHOD_NO_CYCLE 0
#define GRADIENT_CYCLE_METHOD_REFLECT 1
#define GRADIENT_CYCLE_METHOD_REPEAT 2

#define GRADIENT_SHADER_VARIANT_GET_CYCLE_METHOD(x) ((x) & 0x3u)
#define GRADIENT_SHADER_VARIANT_GET_IS_LINEAR(x) (((x) & 0x4u) != 0u)

struct GradientStops {
    float fractions[GRADIENT_MAX_FRACTIONS];
    uint colors[GRADIENT_MAX_FRACTIONS];
};

vec4 interpolateMultiGradient(float t, GradientStops stops) {
    // STEP 1: apply the cycle method
    uint cycleMethod = GRADIENT_SHADER_VARIANT_GET_CYCLE_METHOD(const_ShaderVariant);
    if (cycleMethod > GRADIENT_CYCLE_METHOD_NO_CYCLE) {
        float ft = floor(t);
        t = t - ft;
        if (cycleMethod == GRADIENT_CYCLE_METHOD_REFLECT && (int(ft) % 2 != 0)) {
            t = 1.0 - t;
        }
    } else {
        t = clamp(t, 0.0, 1.0);
    }

    // STEP 2: find the n such that t belongs to ( fractions[n], fractions[n+1] ]
    int n = 0;
    for (; n < GRADIENT_MAX_FRACTIONS - 1; ++n) {
        if (t <= stops.fractions[n + 1]) break;
    }

    // STEP 3: convert to an interval-local interpolation factor
    t = (t - stops.fractions[n]) / (stops.fractions[n + 1] - stops.fractions[n]);

    // STEP 4: interpolate
    vec4 color = mix(decodeColor(stops.colors[n]), decodeColor(stops.colors[n + 1]), t);
    if (GRADIENT_SHADER_VARIANT_GET_IS_LINEAR(const_ShaderVariant)) {
        color = vec4(fromLinear3(color.rgb), color.a);
    }

    return color;
}
