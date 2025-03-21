#version 450

layout(push_constant) uniform PushConstants {
    mat2x3 transform;
} push;

layout(location = 0) in ivec4 in_PositionOffsetAndScanline;
layout(location = 1) in  vec4 in_Color;

// This starts with "Origin" and not "Position" intentionally.
// When drawing, vertices are ordered in a such way, that provoking vertex is always the top-left one.
// This gives us an easy way to calculate offset within the rectangle without additional inputs.
layout(location = 0) out flat ivec4 out_OriginOffsetAndScanline;
layout(location = 1) out flat  vec4 out_Color;

void main() {
    gl_Position = vec4(vec3(in_PositionOffsetAndScanline.xy, 1)*push.transform, 0.0, 1.0);
    out_OriginOffsetAndScanline = in_PositionOffsetAndScanline;
    out_Color = in_Color;
}