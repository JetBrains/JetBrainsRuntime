#ifndef COMMON_H
#define COMMON_H

#include <simd/SIMD.h>

enum VertexAttributes {
    VertexAttributePosition = 0,
    VertexAttributeColor = 1,
    VertexAttributeTexPos = 2
};

enum BufferIndex  {
    MeshVertexBuffer = 0,
    FrameUniformBuffer = 1,
};

struct FrameUniforms {
    matrix_float4x4 projectionViewModel;
};

struct Vertex {
    float position[3];
    unsigned char color[4];
    float txtpos[2];
};

#endif
