#version 450

layout(binding = 0) uniform sampler2D u_TexSampler;
layout(location = 0) in  vec2 in_TexCoord;
layout(location = 0) out vec4 out_Color;

void main() {
    // TODO consider in/out alpha type.
    out_Color = texture(u_TexSampler, in_TexCoord);
}
