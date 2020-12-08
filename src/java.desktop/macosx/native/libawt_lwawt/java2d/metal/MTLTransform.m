#include "MTLTransform.h"

#include <jni.h>
#include <simd/simd.h>

#include "common.h"

@implementation MTLTransform {
    jboolean      _useTransform;
    simd_float4x4 _transform4x4;
    simd_float4x4 _normalize4x4; // just a buffer for setVertexMatrix
}

- (id)init {
    self = [super init];
    if (self) {
        memset(&_normalize4x4, 0, sizeof(_normalize4x4));
        _normalize4x4.columns[3][0] = -1.f;
        _normalize4x4.columns[3][1] = 1.f;
        _normalize4x4.columns[3][3] = 1.0;

        _useTransform = JNI_FALSE;
    }
    return self;
}

- (BOOL)isEqual:(MTLTransform *)other {
    if (self == other)
        return YES;
    return _useTransform == other->_useTransform
           && simd_equal(_transform4x4, other->_transform4x4);
}

- (void)copyFrom:(MTLTransform *)other {
    _useTransform = other->_useTransform;
    if (_useTransform) {
        _transform4x4 = other->_transform4x4;
    }
}

- (void)setTransformM00:(jdouble) m00 M10:(jdouble) m10
                    M01:(jdouble) m01 M11:(jdouble) m11
                    M02:(jdouble) m02 M12:(jdouble) m12 {
    memset(&(_transform4x4), 0, sizeof(_transform4x4));
    _transform4x4.columns[0][0] = m00;
    _transform4x4.columns[0][1] = m10;
    _transform4x4.columns[1][0] = m01;
    _transform4x4.columns[1][1] = m11;
    _transform4x4.columns[3][0] = m02;
    _transform4x4.columns[3][1] = m12;
    _transform4x4.columns[3][3] = 1.0;
    _useTransform = JNI_TRUE;
}

- (void)resetTransform {
    _useTransform = JNI_FALSE;
}

- (void)setVertexMatrix:(id<MTLRenderCommandEncoder>)encoder
              destWidth:(NSUInteger)dw
             destHeight:(NSUInteger)dh {
    // update matrix for vertex shader
    _normalize4x4.columns[0][0] = 2/(double)dw;
    _normalize4x4.columns[1][1] = -2/(double)dh;

    if (_useTransform) {
        simd_float4x4 vertexMatrix = simd_mul(_normalize4x4, _transform4x4);
        [encoder setVertexBytes:&(vertexMatrix) length:sizeof(vertexMatrix) atIndex:MatrixBuffer];
    } else {
        [encoder setVertexBytes:&(_normalize4x4) length:sizeof(_normalize4x4) atIndex:MatrixBuffer];
    }
}

@end


