#version 450

layout(push_constant) uniform PushConstants {
    vec4 fragColor;
} pushConstants;

const vec2 positions[4] = vec2[4](
    vec2(-1.0, -1.0),
    vec2( 1.0, -1.0),
    vec2(-1.0,  1.0),
    vec2( 1.0, 1.0)
);

layout(location = 0) out flat vec4 fragColor;

void main() {
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    fragColor = pushConstants.fragColor;
}
