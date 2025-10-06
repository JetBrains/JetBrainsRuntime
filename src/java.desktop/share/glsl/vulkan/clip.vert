#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in ivec2 in_Position;

void main() {
    gl_Position = transformToDeviceSpace(in_Position);
}