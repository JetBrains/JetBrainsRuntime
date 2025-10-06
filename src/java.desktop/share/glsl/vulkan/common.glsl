
#ifdef STAGE_VERT
layout(push_constant) uniform PushConstants { mat2x3 transform; } push;

vec4 transformToDeviceSpace(vec2 v) {
    return vec4(vec3(v, 1.0) * push.transform, 0.0, 1.0);
}
#endif

#define ALPHA_TYPE_PRE_MULTIPLIED 0U
#define ALPHA_TYPE_STRAIGHT       1U

layout (constant_id = 0) const uint const_InAlphaType  = ALPHA_TYPE_PRE_MULTIPLIED;
layout (constant_id = 1) const uint const_OutAlphaType = ALPHA_TYPE_PRE_MULTIPLIED;

vec4 convertAlpha(vec4 color, uint inType, uint outType) {
    if (inType == ALPHA_TYPE_STRAIGHT && outType == ALPHA_TYPE_PRE_MULTIPLIED) {
        return vec4(color.rgb * color.a, color.a);
    } else if (inType == ALPHA_TYPE_PRE_MULTIPLIED && outType == ALPHA_TYPE_STRAIGHT && color.a > 0.0) {
        return vec4(color.rgb / color.a, color.a);
    } else return color;
}

vec4 convertAlpha(vec4 color) {
    return convertAlpha(color, const_InAlphaType, const_OutAlphaType);
}

vec4 decodeColor(uint srgb) {
    return vec4((uvec4(srgb) >> uvec4(16, 8, 0, 24)) & 0xFFU) / 255.0;
}
