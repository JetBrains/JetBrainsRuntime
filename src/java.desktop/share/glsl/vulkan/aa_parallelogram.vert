#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

layout(location = 0) in vec4 in_OuterInnerOrigins;
layout(location = 1) in vec4 in_OuterSides;
layout(location = 2) in vec4 in_InnerSides;
layout(location = 3) in uint in_Data;

const float AA_NUDGE = 0.51; // > 0.5 with slack for rounding

void main() {
    mat2 outerSides = mat2(in_OuterSides.xy, in_OuterSides.zw);
    mat2 innerSides = mat2(in_InnerSides.xy, in_InnerSides.zw);
    vec2 outerOrigin = in_OuterInnerOrigins.xy;
    vec2 innerOrigin = in_OuterInnerOrigins.zw;

    // Choose a shape from two candidates to reduce overdraw:

    // 1. 'nudged pgram ring' - an expanded outer pgram with a hole in the shape of the shrunk inner pgram.
    // Topology: two nested pgram - the outer boundary and the inner 'hole'.
    // This is achieved with a triangle strip of 10 vertices in this order (oTL = 'outer top-left', etc.):
    // oTL, iTL, oTR, iTR, oBR, iBR, oBL, iBL, oTL, iTL
    // The names 'top-left' etc. are assuming positive d21 and d12, but this works with any orientation.
    float outerCrossAbs = abs(determinant(outerSides));
    vec2 outerNudgeFactor = AA_NUDGE * vec2(abs(outerSides[1].x) + abs(outerSides[1].y), abs(outerSides[0].x) + abs(outerSides[0].y)) / outerCrossAbs;
    vec2 outerNudgedPreImageSize = 1.0 + 2.0 * outerNudgeFactor;
    float outerNudgedPgramArea = outerCrossAbs * outerNudgedPreImageSize.x * outerNudgedPreImageSize.y;

    float innerCrossAbs = abs(determinant(innerSides));

    // We will have nan/inf here in case of innerCrossAbs == 0 (degenerate inner pgram).
    // In that case, these values are guaranteed to be unused later, because innerNudgedPgramValid checks innerCrossAbs.
    vec2 innerNudgeFactor = -AA_NUDGE * vec2(abs(innerSides[1].x) + abs(innerSides[1].y), abs(innerSides[0].x) + abs(innerSides[0].y)) / innerCrossAbs;
    vec2 innerNudgedPreImageSize = 1.0 + 2.0 * innerNudgeFactor;

    bool innerNudgedPgramValid = innerCrossAbs > 0.0 && innerNudgedPreImageSize.x > 0.0 && innerNudgedPreImageSize.y > 0.0;
    float innerNudgedPgramArea = innerNudgedPgramValid ? innerCrossAbs * innerNudgedPreImageSize.x * innerNudgedPreImageSize.y : 0.0;

    float nudgedPgramRingArea = outerNudgedPgramArea - innerNudgedPgramArea;

    // 2. 'bbox' - the bounding box expanded by 0.5 in all directions - fallback for very acute pgrams.
    vec2 outerPoints[] = vec2[](outerOrigin, outerOrigin + outerSides[0], outerOrigin + outerSides[0] + outerSides[1], outerOrigin + outerSides[1]);
    vec2 bboxMin = min(min(outerPoints[0], outerPoints[1]), min(outerPoints[2], outerPoints[3])) - AA_NUDGE;
    vec2 bboxMax = max(max(outerPoints[0], outerPoints[1]), max(outerPoints[2], outerPoints[3])) + AA_NUDGE;
    vec2 bboxSize = bboxMax - bboxMin;
    float bboxArea = bboxSize.x * bboxSize.y;

    vec2 position;
    if (nudgedPgramRingArea < bboxArea) { // use pgram ring?
        bvec2 pgramRingCornerIndex = bvec2((((gl_VertexIndex >> 1) + 1) & 2) != 0, ((gl_VertexIndex >> 1) & 2) != 0);
        position = (gl_VertexIndex & 1) != 0 // is this an inner corner?
            ? (innerNudgedPgramValid
                ? innerOrigin + innerSides * mix(-innerNudgeFactor, 1.0 + innerNudgeFactor, pgramRingCornerIndex)
                : outerOrigin + outerSides * (-outerNudgeFactor)) // in case the inner pgram is inverted after nudging, collapse it to the outer pgram's nudged top-left vertex instead
            : outerOrigin + outerSides * mix(-outerNudgeFactor, 1.0 + outerNudgeFactor, pgramRingCornerIndex);
    } else {
        // fall back to bbox with vertices oTL, oTR, oBL, oBR, oBR, oBR, oBR, oBR, oBR, oBR (6 redundant copies at the end)
        int bboxVertexIndex = min(3, gl_VertexIndex);
        bvec2 bboxCornerIndex = bvec2((bboxVertexIndex & 1) != 0, (bboxVertexIndex & 2) != 0);
        position = mix(bboxMin, bboxMax, bboxCornerIndex);
    }

    gl_Position = transformToDeviceSpace(position);
    passVertexData(in_Data);
    out_GenericFlatVec4_1 = in_OuterInnerOrigins;
    out_GenericFlatVec4_2 = in_OuterSides;
    out_GenericFlatVec4_3 = in_InnerSides;
}