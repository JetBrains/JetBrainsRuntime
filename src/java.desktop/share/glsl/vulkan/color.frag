#version 450
#extension GL_GOOGLE_include_directive: require
#include "common.glsl"

void main() {
    outputColorWithCompositePreApplied(in_Color);
}
