#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(push_constant) uniform PushConstants {
    mat2x3 _; // Offset
    uint  xorColor;
    float extraAlpha;
} push;

layout(set = 0, binding = 0) uniform texture2D u_Texture;
layout(set = 1, binding = 0) uniform sampler u_Sampler;
layout(location = 0) in  vec2 in_TexCoord;
layout(location = 0) out vec4 out_Color;

void main() {
    out_Color = convertAlpha(texture(sampler2D(u_Texture, u_Sampler), in_TexCoord));
    if (const_OutAlphaType == ALPHA_TYPE_STRAIGHT) {
        if (push.extraAlpha < 0.0) { // XOR mode outputs straight alpha.
            uvec4 xor = uvec4(push.xorColor) >> uvec4(16, 8, 0, 24);
            xor = (uvec4(out_Color * 255.0) ^ xor) & 0xFFU;
            out_Color = vec4(xor) / 255.0;
        } else out_Color.a *= push.extraAlpha;
    } else out_Color *= push.extraAlpha;
}
