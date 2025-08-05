#version 450

layout(location = 0) in flat vec4 in_Color;
layout(location = 0) out vec4 out_Color;

void main() {
    out_Color = in_Color;
}
