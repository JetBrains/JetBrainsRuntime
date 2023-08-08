#version 450

layout(push_constant) uniform Push {
    vec2 invViewport2; // 2.0/viewport
} push;

vec4 colors[3] = vec4[](
vec4(1,0,0,1),
vec4(0,1,0,1),
vec4(0,0,1,1)
);

layout(location = 0) in vec2 inPosition;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = colors[gl_VertexIndex % 3];
    gl_Position = vec4(inPosition * push.invViewport2 - 1.0, 0.0, 1.0);
    gl_PointSize = 1.0f;
}
