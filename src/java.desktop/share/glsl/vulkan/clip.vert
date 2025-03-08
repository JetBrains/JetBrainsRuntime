#version 450

layout(push_constant) uniform PushConstants {
    mat2x3 transform;
} push;

layout(location = 0) in ivec2 in_Position;

void main() {
    gl_Position = vec4(vec3(in_Position, 1)*push.transform, 0.0, 1.0);
}