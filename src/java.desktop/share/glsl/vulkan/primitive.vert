#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in  vec2 in_Position;
layout(location = 1) in  uint in_Data;
layout(location = 0) out vec2 out_Position;
layout(location = 1) out uint out_Data;
layout(location = 2) out flat ivec4 _; // Unused output

void main() {
    out_Position = in_Position;
    out_Data = in_Data;
    gl_Position = transformToDeviceSpace(in_Position);
}