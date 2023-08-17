#version 450

vec2 positions[3] = vec2[](
vec2(0.0, -0.5),
vec2(0.5, 0.5),
vec2(-0.5, 0.5)
);

vec4 colors[3] = vec4[](
vec4(1,0,0,1),
vec4(0,1,0,1),
vec4(0,0,1,1)
);

layout(location = 0) out vec4 outColor;

void main() {
    outColor = colors[gl_VertexIndex];
    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
}
