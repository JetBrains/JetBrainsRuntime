#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in vec4  in_RRectCorners;
layout(location = 1) in float in_RRectRadius;
layout(location = 2) in uint  in_Data;

const float AA_NUDGE_MAGNITUDE = 0.51;
vec2 AA_VERTEX_NUDGE[] = {
    vec2(-AA_NUDGE_MAGNITUDE, -AA_NUDGE_MAGNITUDE), // top-left
    vec2(+AA_NUDGE_MAGNITUDE, -AA_NUDGE_MAGNITUDE), // top-right
    vec2(-AA_NUDGE_MAGNITUDE, +AA_NUDGE_MAGNITUDE), // bottom-left
    vec2(+AA_NUDGE_MAGNITUDE, +AA_NUDGE_MAGNITUDE)  // bottom-right
};

void main() {
    gl_Position = transformToDeviceSpace(vec2(in_RRectCorners[(gl_VertexIndex & 1) << 1], in_RRectCorners[(gl_VertexIndex & 2) + 1]) + AA_VERTEX_NUDGE[gl_VertexIndex]);
    passVertexData(in_Data);
    out_GenericFlatVec4_1 = in_RRectCorners;
    out_GenericFlatVec4_2 = vec4(in_RRectRadius, 0, 0, 0);
}