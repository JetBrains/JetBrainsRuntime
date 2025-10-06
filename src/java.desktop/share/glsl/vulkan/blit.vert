#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in  vec2 in_Position;
layout(location = 1) in  vec2 in_TexCoord;
layout(location = 0) out vec2 out_TexCoord;

void main() {
    gl_Position = transformToDeviceSpace(in_Position);
    out_TexCoord = in_TexCoord;
}