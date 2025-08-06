#version 450

layout(push_constant) uniform PushConstants {
    vec2 viewportNormalizer; // 2.0 / viewport
} push;

layout(location = 0) in ivec2 in_Position;

void main() {
    gl_Position = vec4(vec2(in_Position) * push.viewportNormalizer - vec2(1.0), 0.0, 1.0);
}