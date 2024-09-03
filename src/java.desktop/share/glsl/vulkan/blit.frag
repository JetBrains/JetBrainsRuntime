#version 450

layout(binding = 0) uniform sampler2D u_TexSampler;
layout(location = 0) in  vec2 in_TexCoord;
layout(location = 0) out vec4 out_Color;

void main() {
    out_Color = texture(u_TexSampler, in_TexCoord);
    out_Color.a = 1.0; // TODO figure out what's in the image alpha channel.
}
