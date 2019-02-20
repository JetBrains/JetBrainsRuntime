// Header for types and enum constants shared between Metal shaders and Objective C source

#ifndef MetalShaderTypes_h
#define MetalShaderTypes_h

//#import <metal_stdlib>
//using namespace metal;


#import <simd/simd.h>
//#import <Metal/Metal.h>


typedef struct
{
    // Positions in pixel space
    vector_float4 position;

    // Floating-point RGBA colors
    vector_float4 color;
} MetalVertex;


#endif /* MetalShaderTypes_h */
