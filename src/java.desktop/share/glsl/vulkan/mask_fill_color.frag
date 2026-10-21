#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(set = 0, binding = 0, r8) uniform readonly restrict imageBuffer u_Mask;

layout(origin_upper_left) in vec4 gl_FragCoord;

layout(location = 0) in flat ivec4 in_OriginOffsetAndScanline;
layout(location = 1) in flat  vec4 in_Color;

COLOR_OUT_DECL()

void main() {
    OUTPUT_SKIP_COMPOSITE(in_Color);
}
