#version 450

layout(push_constant) uniform PushConstants {
    mat2x3 transform;
} push;

layout(location = 0) in vec2 in_Position;
layout(location = 1) in vec4 in_Color;
layout(location = 0) out flat vec4 out_Color;

void main() {
    gl_Position = vec4(vec3(in_Position, 1)*push.transform, 0.0, 1.0);
    out_Color = in_Color;
}