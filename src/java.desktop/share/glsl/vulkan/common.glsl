
// Shader specialization.
#define SHADER_MOD_XOR  1U // Xor composite mode
#define SHADER_MOD_MASK 2U // MASK_FILL / MASK_BLIT

layout (constant_id = 0) const uint const_InAlphaType    = 0;
layout (constant_id = 1) const uint const_OutAlphaType   = 0;
layout (constant_id = 2) const uint const_ShaderVariant  = 0;
layout (constant_id = 3) const uint const_ShaderModifier = 0;

// Host structs.
struct VKTransform {
    float m00, m01, m02;
    float m10, m11, m12;
};
struct VKCompositeConstants {
    uint xorColor;
    float extraAlpha;
};

// Vertex shader transformation support.
#ifdef STAGE_VERT
layout(push_constant) uniform PushConstants { VKTransform transform; } push;

vec4 transformToDeviceSpace(vec2 v) {
    return vec4(vec3(v, 1.0) * mat2x3(push.transform.m00, push.transform.m01, push.transform.m02, push.transform.m10, push.transform.m11, push.transform.m12), 0.0, 1.0);
}
#endif

// Fragment shader push constant support.
#ifdef STAGE_FRAG
#define PUSH_CONSTANTS_IMPL(STATEMENT) \
    layout(push_constant) uniform PushConstants { VKTransform _; VKCompositeConstants push_composite; STATEMENT }
#define DEFAULT_PUSH_CONSTANTS() PUSH_CONSTANTS_IMPL(STAGE_FRAG)
#define PUSH_CONSTANTS(TYPE) PUSH_CONSTANTS_IMPL(TYPE push;)
#endif

// Color conversion support.
#define ALPHA_TYPE_PRE_MULTIPLIED 0U
#define ALPHA_TYPE_STRAIGHT       1U

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

// When applying alpha to a color, straight alpha only multiplies alpha,
// and pre-multiplied multiplies the whole color. Use this for convenience.
vec4 alphaMask(float alpha, uint alphaType) {
    return alphaType == ALPHA_TYPE_PRE_MULTIPLIED ? vec4(alpha) : vec4(1.0, 1.0, 1.0, alpha);
}

// Decode color from uint-packed ARGB components.
vec4 decodeColor(uint srgb) {
    return vec4((uvec4(srgb) >> uvec4(16, 8, 0, 24)) & 0xFFU) / 255.0;
}

#ifdef STAGE_FRAG
// Before outputting the color, some post-processing is needed:
// - For alpha composite, apply extra alpha.
// - For XOR composite, apply xor.
vec4 applyComposite(vec4 color, VKCompositeConstants composite) {
    if ((const_ShaderModifier & SHADER_MOD_XOR) != 0) {
        uvec4 xor = uvec4(composite.xorColor) >> uvec4(16, 8, 0, 24);
        xor = (uvec4(color * 255.0) ^ xor) & 0xFFU;
        return vec4(xor) / 255.0;
    } else return color * alphaMask(composite.extraAlpha, const_OutAlphaType);
}
#define APPLY_COMPOSITE(COLOR) applyComposite(COLOR, push_composite)

// MASK_FILL / MASK_BLIT support.
int calculateMaskIndex(vec2 localCoord, ivec4 originOffsetAndScanline) {
    ivec2 maskPos = ivec2(localCoord - vec2(originOffsetAndScanline.xy));
    int offset = originOffsetAndScanline.z;
    int scanline = originOffsetAndScanline.w;
    return offset + scanline * maskPos.y + min(scanline, maskPos.x);
}
vec4 applyMaskOp(vec4 color, float mask) {
    if ((const_ShaderModifier & SHADER_MOD_XOR) != 0) return color * float(mask > 0.0);
    else return color * alphaMask(mask, const_OutAlphaType);
}
#define APPLY_MASK(COLOR) applyMaskOp(COLOR, imageLoad(u_Mask, calculateMaskIndex(gl_FragCoord.xy, in_OriginOffsetAndScanline)).r)

// Generic shader support.
#define GENERIC_INOUT()                                            \
    layout(location = 0) out      vec4 out_Color;                  \
    layout(location = 0) in       vec2 in_Position;                \
    layout(location = 1) in flat  uint in_Data;                    \
    layout(location = 2) in flat ivec4 in_OriginOffsetAndScanline; \
    layout(origin_upper_left) in  vec4 gl_FragCoord;               \
    layout(set = 0, binding = 0, r8) uniform readonly restrict imageBuffer u_Mask

// Generic color output - handles composite and mask automatically.
#define OUTPUT(COLOR) out_Color = COLOR; out_Color = APPLY_COMPOSITE(out_Color); \
    if ((const_ShaderModifier & SHADER_MOD_MASK) != 0) out_Color = APPLY_MASK(out_Color)
#endif
