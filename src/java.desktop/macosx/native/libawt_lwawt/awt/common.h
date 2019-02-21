#ifndef COMMON_H
#define COMMON_H

#include <simd/SIMD.h>

#define PGRAM_VERTEX_COUNT 6

enum VertexAttributes {
    VertexAttributePosition = 0,
    VertexAttributeTexPos = 1
};

enum BufferIndex  {
    MeshVertexBuffer = 0,
    FrameUniformBuffer = 1,
};

struct FrameUniforms {
    vector_float4 color;
};

struct Vertex {
    float position[3];
};

struct TxtVertex {
    float position[3];
    float txtpos[2];
};

#endif
