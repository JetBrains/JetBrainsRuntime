#version 450

layout(set = 0, binding = 0) uniform texture2D u_Texture;
layout(set = 1, binding = 0) uniform sampler u_Sampler;
layout(location = 0) in  vec2 in_TexCoord;
layout(location = 0) out vec4 out_Color;

void main() {
    // TODO consider in/out alpha type.
    out_Color = texture(sampler2D(u_Texture, u_Sampler), in_TexCoord);
}
