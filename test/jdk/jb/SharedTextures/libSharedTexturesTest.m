#define GL_SILENCE_DEPRECATION

#import <jni.h>

#import <Foundation/Foundation.h>

#import <Metal/Metal.h>

#import <OpenGL/CGLCurrent.h>
#import <OpenGL/CGLTypes.h>
#import <OpenGL/OpenGL.h>
#import <OpenGL/gl3.h>

static int METAL_TEXTURE_TYPE = 1;
static int OPENGL_TEXTURE_TYPE = 2;

static int gTextureType = -1;

static CGLContextObj gOpenGLContext = 0;

static CGLContextObj gSharedContext = 0;
static CGLPixelFormatObj gPixelFormat = 0;

static void throw(JNIEnv *env, const char* msg) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), msg);
}

static const char* gl_error_string(GLenum err) {
    switch (err) {
        case GL_NO_ERROR: return "GL_NO_ERROR";
        case GL_INVALID_ENUM: return "GL_INVALID_ENUM";
        case GL_INVALID_VALUE: return "GL_INVALID_VALUE";
        case GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
        case GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY";

        default: return "Unknown GL error";
    }
}

static void check_gl_error(JNIEnv *env, const char* msgPrefix) {
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        char msg[256];
        snprintf(msg, sizeof(msg), "%s: OpenGL error %d (%s)", msgPrefix, err, gl_error_string(err));
        throw(env, msg);
    }
}

static id <MTLTexture>
createMTLTextureFromNSData(id <MTLDevice> device, NSData *textureData, NSUInteger width, NSUInteger height) {
    if (!device || !textureData) {
        NSLog(@"Invalid device or texture data.");
        return nil;
    }

    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                          width:width
                                                                                         height:height
                                                                                      mipmapped:NO];
    descriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    descriptor.storageMode = MTLStorageModeShared;

    id <MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
    if (!texture) {
        NSLog(@"Failed to create Metal texture.");
        return nil;
    }

    NSUInteger bytesPerPixel = 4;
    NSUInteger bytesPerRow = bytesPerPixel * width;
    MTLRegion region = MTLRegionMake2D(0, 0, width, height);
    [texture replaceRegion:region mipmapLevel:0 withBytes:textureData.bytes bytesPerRow:bytesPerRow];

    return texture;
}

static jlong createTextureMTL(JNIEnv *env, jbyteArray byteArray, jint width, jint height) {
    @autoreleasepool {
        id <MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) {
            NSLog(@"Metal is not supported on this device.");
            return 0;
        }

        jsize length = (*env)->GetArrayLength(env, byteArray);
        jbyte *bytes = (*env)->GetByteArrayElements(env, byteArray, NULL);
        NSMutableData *textureData = [NSMutableData dataWithBytes:bytes length:length];

        id <MTLTexture> texture = createMTLTextureFromNSData(device, textureData, width, height);
        (*env)->ReleaseByteArrayElements(env, byteArray, bytes, 0);
        return (jlong) texture;
    }
}

static jlong createTextureOpenGL(JNIEnv *env, jbyteArray byteArray, jint width, jint height) {
    CGLSetCurrentContext(gSharedContext);

    jbyte *pixels = (*env)->GetByteArrayElements(env, byteArray, NULL);

    GLuint texId = 0;
    glGenTextures(1, &texId);
    check_gl_error(env, "glGenTextures");
    glBindTexture(GL_TEXTURE_2D, texId);
    check_gl_error(env, "glBindTexture");

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    check_gl_error(env, "glTexParameteri GL_TEXTURE_MAG_FILTER");

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    check_gl_error(env, "glTexParameteri GL_TEXTURE_MIN_FILTER");

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    check_gl_error(env, "glTexParameteri GL_TEXTURE_WRAP_S GL_CLAMP_TO_EDGE");

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    check_gl_error(env, "glTexParameteri GL_TEXTURE_WRAP_T GL_CLAMP_TO_EDGE");

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, (const void*)pixels);
    check_gl_error(env, "glTexImage2D");

    (*env)->ReleaseByteArrayElements(env, byteArray, pixels, JNI_ABORT);

    glBindTexture(GL_TEXTURE_2D, 0);

    glFinish();
    check_gl_error(env, "glFinish");

    CGLSetCurrentContext(0);

    return texId;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_setSharedContextInfo
        (JNIEnv *env, jclass clazz, jlongArray sharedContextInfo) {
    jsize length = (*env)->GetArrayLength(env, sharedContextInfo);
    if (length != 2) {
            (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
            "Unexpected shared context info size");
    }

    jlong contextInfo[2];
    (*env)->GetLongArrayRegion(env, sharedContextInfo, 0, length, contextInfo);
    gSharedContext = (CGLContextObj)contextInfo[0];
    gPixelFormat = (CGLPixelFormatObj)contextInfo[1];
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_initNative
        (JNIEnv *env, jclass clazz, jint textureType) {
    gTextureType = textureType;
    if (textureType == METAL_TEXTURE_TYPE) {
        // nothing
    } else if (textureType == OPENGL_TEXTURE_TYPE) {
        CGLError error = CGLCreateContext(gPixelFormat, gSharedContext, &gOpenGLContext);
        if (error != kCGLNoError || !gOpenGLContext) {
            throw(env, "Failed to init OpenGL context");
        }
    } else {
        throw(env, "Unknown texture type");
    }
}

JNIEXPORT jlong JNICALL Java_SharedTexturesTest_createTexture
        (JNIEnv *env, jclass clazz, jbyteArray byteArray, jint width, jint height) {
    if (gTextureType == METAL_TEXTURE_TYPE) {
        return createTextureMTL(env, byteArray, width, height);
    } else if (gTextureType == OPENGL_TEXTURE_TYPE) {
        return createTextureOpenGL(env, byteArray, width, height);
    }

    throw(env, "Unknown texture type");
    return 0;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_disposeTexture
        (JNIEnv *env, jclass clazz, jlong texture) {
    if (gTextureType == METAL_TEXTURE_TYPE) {
        id <MTLTexture> mtlTexture = (__bridge id <MTLTexture>) (void *) texture;
        if (mtlTexture != nil) {
            [mtlTexture release];
        }
    } else if (gTextureType == OPENGL_TEXTURE_TYPE) {
        GLuint texId = (GLuint)texture;
        glDeleteTextures(1, &texId);
    }
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_releaseContext
        (JNIEnv *env, jclass clazz) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        if (gOpenGLContext != 0) {
            CGLSetCurrentContext(0);
            CGLDestroyContext(gOpenGLContext);
            gOpenGLContext = 0;
        }
    }
}

