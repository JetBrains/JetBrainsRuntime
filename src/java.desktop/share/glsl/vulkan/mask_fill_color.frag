#version 450

layout(set = 0, binding = 0, r8) uniform readonly restrict imageBuffer u_Mask;

layout(origin_upper_left) in vec4 gl_FragCoord;

layout(location = 0) in flat ivec4 in_OriginOffsetAndScanline;
layout(location = 1) in flat  vec4 in_Color;

layout(location = 0) out vec4 out_Color;

void main() {
    ivec2 maskPos = ivec2(gl_FragCoord.xy) - in_OriginOffsetAndScanline.xy;
    int offset = in_OriginOffsetAndScanline.z;
    int scanline = in_OriginOffsetAndScanline.w;
    int maskIndex = offset + scanline * maskPos.y + min(scanline, maskPos.x);
    out_Color = in_Color * imageLoad(u_Mask, maskIndex).r;
}
