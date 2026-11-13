#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in ivec4 in_PositionOffsetAndScanline;
layout(location = 1) in  uint in_Data;

void main() {
    gl_Position = transformToDeviceSpace(in_PositionOffsetAndScanline.xy);
    passVertexData(in_Data);
    // When drawing, vertices are ordered in a such way, that provoking vertex is always the top-left one.
    // This gives us an easy way to calculate offset within the rectangle without additional inputs.
    out_GenericFlatIvec4_1 = in_PositionOffsetAndScanline;
}