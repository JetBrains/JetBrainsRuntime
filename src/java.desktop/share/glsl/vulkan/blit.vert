#version 450

layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 texPosition;
layout(location = 0) out vec2 fragTexCoord;

void main() {
    gl_Position = vec4(inPosition, 0.0, 1.0);
    fragTexCoord = texPosition;
}