#version 450
#extension GL_GOOGLE_include_directive: require
#define ALPHA_TYPE_SPEC_INDEX 0
#include "alpha_type.glsl"

layout(set = 0, binding = 0) uniform texture2D u_Texture;
layout(set = 1, binding = 0) uniform sampler u_Sampler;
layout(location = 0) in  vec2 in_TexCoord;
layout(location = 0) out vec4 out_Color;

void main() {
    out_Color = convertAlpha(texture(sampler2D(u_Texture, u_Sampler), in_TexCoord));
}
