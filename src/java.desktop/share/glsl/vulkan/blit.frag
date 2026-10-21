#version 450
#extension GL_GOOGLE_include_directive: require
#define SHADER_NO_MASK_BUFFER
#include "common.glsl"
DEFAULT_PUSH_CONSTANTS();

layout(set = 0, binding = 0) uniform texture2D u_Texture;
layout(set = 1, binding = 0) uniform sampler u_Sampler;
layout(location = 0) in  vec2 in_TexCoord;

void main() {
    outputColor(convertAlpha(texture(sampler2D(u_Texture, u_Sampler), in_TexCoord)));
}
