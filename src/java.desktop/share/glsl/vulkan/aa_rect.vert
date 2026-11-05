#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in vec4 in_OuterCorners;
layout(location = 1) in vec4 in_InnerCorners;
layout(location = 2) in vec2 in_OuterInnerRadii;
layout(location = 3) in uint in_Data;

const float BOX_NUDGE_LENGTH = 0.51;

void main() {
    vec2 position;
    bvec2 cornerIndex = bvec2((((gl_VertexIndex >> 1) + 1) & 2) != 0, ((gl_VertexIndex >> 1) & 2) != 0);
    float innerNudgeLength = (sqrt(2.0) - 1.0) * in_OuterInnerRadii.y + BOX_NUDGE_LENGTH;
    vec4 nudgedInnerCorners = vec4(in_InnerCorners.xy + innerNudgeLength, in_InnerCorners.zw - innerNudgeLength);
    position = (gl_VertexIndex & 1) != 0 // is this an inner corner?
        ? (nudgedInnerCorners.x < nudgedInnerCorners.z && nudgedInnerCorners.y < nudgedInnerCorners.w
            ? mix(nudgedInnerCorners.xy, nudgedInnerCorners.zw, cornerIndex)
            : in_OuterCorners.xy - BOX_NUDGE_LENGTH) // in case the inner rectangle is inverted after nudging, collapse it to the outer rect's nudged top-left vertex instead
        : mix(in_OuterCorners.xy - BOX_NUDGE_LENGTH, in_OuterCorners.zw + BOX_NUDGE_LENGTH, cornerIndex);

    gl_Position = transformToDeviceSpace(position);
    passVertexData(in_Data);
    out_GenericFlatVec4_1 = in_OuterCorners;
    out_GenericFlatVec4_2 = in_InnerCorners;
    out_GenericFlatVec4_3 = vec4(in_OuterInnerRadii, 0, 0);
}