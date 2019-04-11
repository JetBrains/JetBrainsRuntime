#include "MTLUtils.h"

float MTLUtils_normalizeX(id<MTLTexture> dest, float x) { return (2.0*x/dest.width) - 1.0; }
float MTLUtils_normalizeY(id<MTLTexture> dest, float y) { return 2.0*(1.0 - y/dest.height) - 1.0; }

#include <jni.h>
#include <simd/simd.h>
#include "common.h"
#include "Trace.h"

extern void J2dTraceImpl(int level, jboolean cr, const char *string, ...);
void J2dTraceTraceVector(simd_float4 pt) {
    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_FALSE, "[%lf %lf %lf %lf]", pt.x, pt.y, pt.z, pt.w);
}

void checkTransform(struct TxtVertex txpt, simd_float4x4 transform4x4) {
    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_FALSE, "check transform: ");

    simd_float4 fpt = simd_make_float4(txpt.position[0], txpt.position[1], txpt.position[2], 1.f);
    simd_float4 fpt_trans = simd_mul(transform4x4, fpt);
    J2dTraceTraceVector(fpt);
    J2dTraceImpl(J2D_TRACE_VERBOSE, JNI_FALSE, "  ===>>>  ");
    J2dTraceTraceVector(fpt_trans);
    J2dTraceLn(J2D_TRACE_VERBOSE, " ");
}

