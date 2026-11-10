#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

// This is the transform gl_FragCoord -> textureSource
PUSH_CONSTANTS(VKTransform);

layout(set = 1, binding = 0) uniform texture2D u_Texture;
layout(set = 2, binding = 0) uniform sampler u_Sampler;

void main() {
    vec2 uv = applyTransform(push, gl_FragCoord.xy);
    outputColor(convertAlpha(textureLod(sampler2D(u_Texture, u_Sampler), uv, 0.0)));
}