#import "MTLSurfaceData.h"

#import "jni_util.h"

// From MTLSurfaceData.m
extern void MTLSD_SetNativeDimensions(JNIEnv *env, BMTLSDOps *bmtlsdo, jint w, jint h);

static jboolean MTLSurfaceData_initWithTexture(
    BMTLSDOps *bmtlsdo,
    jboolean isOpaque,
    void* pTexture
) {
    @autoreleasepool {
        if (bmtlsdo == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: ops are null");
            return JNI_FALSE;
        }

        if (pTexture == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: texture is null");
            return JNI_FALSE;
        }

        id <MTLTexture> texture = (__bridge id <MTLTexture>) pTexture;
        if (texture == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: failed to cast texture to MTLTexture");
            return JNI_FALSE;
        }

        if (texture.width >= MTL_GPU_FAMILY_MAC_TXT_SIZE || texture.height >= MTL_GPU_FAMILY_MAC_TXT_SIZE ||
            texture.width == 0 || texture.height == 0) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: wrong texture size %d x %d",
                          texture.width, texture.height);
            return JNI_FALSE;
        }

        if (texture.pixelFormat != MTLPixelFormatBGRA8Unorm) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: unsupported pixel format: %d",
                          texture.pixelFormat);
            return JNI_FALSE;
        }

        bmtlsdo->pTexture = texture;
        bmtlsdo->pOutTexture = NULL;

        MTLSDOps *mtlsdo = (MTLSDOps *)bmtlsdo->privOps;
        if (mtlsdo == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: MTLSDOps are null");
            return JNI_FALSE;
        }
        if (mtlsdo->configInfo == NULL || mtlsdo->configInfo->context == NULL) {
            J2dRlsTraceLn(J2D_TRACE_ERROR, "MTLSurfaceData_initWithTexture: MTLSDOps wasn't initialized (context is null)");
            return JNI_FALSE;
        }
        MTLContext* ctx = mtlsdo->configInfo->context;
        MTLTextureDescriptor *stencilDataDescriptor =
                [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR8Uint
                                                                   width:texture.width
                                                                  height:texture.height
                                                               mipmapped:NO];
        stencilDataDescriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        stencilDataDescriptor.storageMode = MTLStorageModePrivate;
        bmtlsdo->pStencilData = [ctx.device newTextureWithDescriptor:stencilDataDescriptor];

        MTLTextureDescriptor *stencilTextureDescriptor =
                [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatStencil8
                                                                   width:texture.width
                                                                  height:texture.height
                                                               mipmapped:NO];
        stencilTextureDescriptor.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
        stencilTextureDescriptor.storageMode = MTLStorageModePrivate;
        bmtlsdo->pStencilTexture = [ctx.device newTextureWithDescriptor:stencilTextureDescriptor];

        bmtlsdo->isOpaque = isOpaque;
        bmtlsdo->width = texture.width;
        bmtlsdo->height = texture.height;
        bmtlsdo->drawableType = MTLSD_RT_TEXTURE;

        [texture retain];

        J2dTraceLn(J2D_TRACE_VERBOSE, "MTLSurfaceData_initTexture: w=%d h=%d bp=%p [tex=%p] opaque=%d sfType=%d",
                   bmtlsdo->width, bmtlsdo->height, bmtlsdo, bmtlsdo->pTexture, isOpaque, bmtlsdo->drawableType);
        return JNI_TRUE;
    }
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_metal_MTLSurfaceDataExt_initWithTexture(
    JNIEnv *env,
    jclass cls,
    jlong pData,
    jboolean isOpaque,
    jlong pTexture
) {
    BMTLSDOps *bmtlsdops = (BMTLSDOps *) pData;
    if (!MTLSurfaceData_initWithTexture(bmtlsdops, isOpaque, jlong_to_ptr(pTexture))) {
        return JNI_FALSE;
    }
    MTLSD_SetNativeDimensions(env, (BMTLSDOps *) pData, bmtlsdops->width, bmtlsdops->height);
    return JNI_TRUE;
}

