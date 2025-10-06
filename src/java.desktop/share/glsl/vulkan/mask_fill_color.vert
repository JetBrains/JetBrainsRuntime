#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in ivec4 in_PositionOffsetAndScanline;
layout(location = 1) in  uint in_Color;

// This starts with "Origin" and not "Position" intentionally.
// When drawing, vertices are ordered in a such way, that provoking vertex is always the top-left one.
// This gives us an easy way to calculate offset within the rectangle without additional inputs.
layout(location = 0) out flat ivec4 out_OriginOffsetAndScanline;
layout(location = 1) out flat  vec4 out_Color;

void main() {
    gl_Position = transformToDeviceSpace(in_PositionOffsetAndScanline.xy);
    out_OriginOffsetAndScanline = in_PositionOffsetAndScanline;
    out_Color = convertAlpha(decodeColor(in_Color));
}