#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in  vec2 in_Position;
layout(location = 1) in  uint in_Data;

void main() {
    gl_Position = transformToDeviceSpace(in_Position);
    passVertexData(in_Data);
}