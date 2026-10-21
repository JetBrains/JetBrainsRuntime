////////////////////////////////////////////////////////////////////////////////
//// SHADER_SPECIALIZATION /////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////
layout (constant_id = 0) const uint const_InAlphaType    = 0;
layout (constant_id = 1) const uint const_OutAlphaType   = 0;
layout (constant_id = 2) const uint const_ShaderVariant  = 0;
layout (constant_id = 3) const uint const_ShaderModifier = 0;

#define SHADER_MOD_XOR (1U << 0) // bit 0 - Xor composite mode

// bits 1-2 - coverage source
#define SHADER_MOD_GET_COVERAGE_SOURCE() ((const_ShaderModifier >> 1) & 3)
#define SHADER_COVERAGE_NONE 0
#define SHADER_COVERAGE_MASK 1
#define SHADER_COVERAGE_AA_RRECT 2

#define SHADER_MOD_PASS_DATA_AS_COLOR (1U << 3) // bit 3 - pass vertex data as out_Color instead of out_Data, used for simple color paints

////////////////////////////////////////////////////////////////////////////////
//// Vertex shader common outputs and transform push constants /////////////////
////////////////////////////////////////////////////////////////////////////////
#ifdef STAGE_VERT
layout(location = 15) out flat uint  out_Data;
layout(location = 14) out flat vec4  out_Color;
layout(location = 13) out flat vec4  out_GenericFlatVec4_1;
layout(location = 12) out flat vec4  out_GenericFlatVec4_2;
layout(location = 11) out flat ivec4 out_GenericFlatIvec4_1;

struct VKTransform {
    float m00, m01, m02;
    float m10, m11, m12;
};
layout(push_constant) uniform PushConstants { VKTransform transform; } push;

vec4 transformToDeviceSpace(vec2 v) {
    return vec4(vec3(v, 1.0) * mat2x3(push.transform.m00, push.transform.m01, push.transform.m02, push.transform.m10, push.transform.m11, push.transform.m12), 0.0, 1.0);
}
#endif // STAGE_VERT

////////////////////////////////////////////////////////////////////////////////
//// Fragment shader common inputs and push constants //////////////////////////
////////////////////////////////////////////////////////////////////////////////
#ifdef STAGE_FRAG
layout(origin_upper_left) in  vec4  gl_FragCoord;
layout(location = 15) in flat uint  in_Data;
layout(location = 14) in flat vec4  in_Color;
layout(location = 13) in flat vec4  in_GenericFlatVec4_1;
layout(location = 12) in flat vec4  in_GenericFlatVec4_2;
layout(location = 11) in flat ivec4 in_GenericFlatIvec4_1;

// TODO: we should change this logic when adding support for masked blits
#ifndef SHADER_NO_MASK_BUFFER
layout(set = 0, binding = 0, r8) uniform readonly restrict imageBuffer u_Mask;
#endif // SHADER_NO_MASK_BUFFER

layout(location = 0, index = 0) out vec4 out_Color;

struct VKCompositeConstants {
    uint xorColor;
    float extraAlpha;
};
#define PUSH_CONSTANTS_IMPL(STATEMENT) \
    layout(push_constant) uniform PushConstants { layout(offset = 24 /* == sizeof(VKTransform) */) VKCompositeConstants push_composite; STATEMENT }
#define DEFAULT_PUSH_CONSTANTS() PUSH_CONSTANTS_IMPL(STAGE_FRAG)
#define PUSH_CONSTANTS(TYPE) PUSH_CONSTANTS_IMPL(TYPE push;)
#endif // STAGE_FRAG

////////////////////////////////////////////////////////////////////////////////
//// Color conversion support //////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////
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

// Convert a color vec3 from linear to sRGB.
// Implementation matching Metal's fromLinear3.
vec3 fromLinear3(vec3 c) {
    // Use approximated calculations to match software rendering
    return 1.055 * pow(c, vec3(0.416667)) - 0.055;
}

#ifdef STAGE_FRAG

////////////////////////////////////////////////////////////////////////////////
//// Composite support /////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////
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

////////////////////////////////////////////////////////////////////////////////
//// Coverage support //////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////
vec4 applyMaskOp(vec4 color, float mask) {
    if ((const_ShaderModifier & SHADER_MOD_XOR) != 0) return color * float(mask > 0.0);
    else return color * alphaMask(mask, const_OutAlphaType);
}

// used for coverage source MASK
int calculateMaskIndex(vec2 localCoord, ivec4 originOffsetAndScanline) {
    ivec2 maskPos = ivec2(localCoord - vec2(originOffsetAndScanline.xy));
    int offset = originOffsetAndScanline.z;
    int scanline = originOffsetAndScanline.w;
    return offset + scanline * maskPos.y + min(scanline, maskPos.x);
}

float getRRectCoverage(vec2 fragCoord, vec4 corners, float radius) {
    return 1.0f; // TODO!
}

float getCoverage(vec2 fragCoord) {
    if (SHADER_MOD_GET_COVERAGE_SOURCE() == SHADER_COVERAGE_AA_RRECT) {
        return getRRectCoverage(fragCoord, in_GenericFlatVec4_1, in_GenericFlatVec4_2.x);
    }
#ifndef SHADER_NO_MASK_BUFFER
    else if(SHADER_MOD_GET_COVERAGE_SOURCE() == SHADER_COVERAGE_MASK) {
        return imageLoad(u_Mask, calculateMaskIndex(fragCoord, in_GenericFlatIvec4_1)).r;
    }
#endif // SHADER_NO_MASK_BUFFER
    else {
        return 1.0f;
    }
}

#endif // STAGE_FRAG

////////////////////////////////////////////////////////////////////////////////
//// Shader public API /////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////
#ifdef STAGE_VERT
void passVertexData(uint data) {
    if ((const_ShaderModifier & SHADER_MOD_PASS_DATA_AS_COLOR) != 0) {
        out_Color = convertAlpha(decodeColor(data));
    } else {
        out_Data = data;
    }
}
#endif // STAGE_VERT

#ifdef STAGE_FRAG
// Generic color output - handles composite and coverage automatically.
void outputColorWithCompositePreApplied(vec4 color) {
    out_Color = color;
    if (SHADER_MOD_GET_COVERAGE_SOURCE() != SHADER_COVERAGE_NONE) {
        float coverage = getCoverage(gl_FragCoord.xy);
        out_Color = applyMaskOp(out_Color, coverage);
    }
}

#define outputColor(COLOR) outputColorWithCompositePreApplied(applyComposite((COLOR), push_composite))

#endif // STAGE_FRAG
