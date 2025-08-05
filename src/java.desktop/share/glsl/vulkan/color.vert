#version 450

layout(push_constant) uniform PushConstants {
    vec4 fragColor;
} pushConstants;

layout(location = 0) in vec2 inPosition;
layout(location = 0) out flat vec4 fragColor;

void main() {
    gl_Position = vec4(inPosition, 0.0, 1.0);
    fragColor = pushConstants.fragColor;
}