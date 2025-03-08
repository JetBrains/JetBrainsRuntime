#version 450

layout(push_constant) uniform PushConstants {
    mat2x3 transform;
} push;

layout(location = 0) in  vec2 in_Position;
layout(location = 1) in  vec2 in_TexCoord;
layout(location = 0) out vec2 out_TexCoord;

void main() {
    gl_Position = vec4(vec3(in_Position, 1.0)*push.transform, 0.0, 1.0);
    out_TexCoord = in_TexCoord;
}