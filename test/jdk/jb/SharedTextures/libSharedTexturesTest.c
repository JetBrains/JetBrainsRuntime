#include <jni.h>

#include <windows.h>
#include <GL/gl.h>
#include <stdlib.h>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h" // Place stb_image_write.h in your project directory


static int gTextureType = -1;

static const METAL_TEXTURE_TYPE = 1;
static const OPENGL_TEXTURE_TYPE = 2;

static HGLRC gSharedContext = 0;
static int gPixelFormat = 0;

static HDC dc = 0;
static HGLRC gGLContext = 0;

typedef void (APIENTRY *glBindTextureType)(GLenum target, GLuint texture);
typedef void (APIENTRY *glGenTexturesType)(GLsizei n, GLuint *textures);
typedef void (APIENTRY *glTexParameteriType)(GLenum target, GLenum pname, GLint param);
typedef void (APIENTRY *glTexImage2DType)(GLenum target, GLint level, GLint internalformat, GLsizei width, GLsizei height, GLint border, GLenum format, GLenum type, const GLvoid *pixels);
typedef void (APIENTRY *glDeleteTexturesType)(GLsizei n, const GLuint *textures);

static glBindTextureType pglBindTexture;
static glGenTexturesType pglGenTextures;
static glTexParameteriType pglTexParameteri;
static glTexImage2DType pglTexImage2D;
static glDeleteTexturesType pglDeleteTextures;

static int initOpenGL() {
    dc = GetDC(0);
    if (!dc) {
        return 0;
    }

    if (!SetPixelFormat(dc, gPixelFormat, 0)) {
        printf("SetPixelFormat failed: %d\n", GetLastError());
        ReleaseDC(0, dc);
        return FALSE;
    }

    gGLContext = wglCreateContext(dc);
    if (!gGLContext) {
        ReleaseDC(0, dc);
        dc = 0;
        return 0;
    }

    if (!wglShareLists(gSharedContext, gGLContext)) {
        ReleaseDC(0, dc);
        dc = 0;
        return 0;
    }

    if (!wglMakeCurrent(dc, gGLContext)) {
        wglDeleteContext(gGLContext);
        gGLContext = 0;
        ReleaseDC(0, dc);
        dc = 0;
        return 0;
    }

    pglGenTextures = glGenTextures; // (glGenTexturesType)wglGetProcAddress("glGenTextures");
    pglBindTexture = glBindTexture; // (glBindTextureType)wglGetProcAddress("glBindTexture");
    pglTexParameteri = glTexParameteri; // (glTexParameteriType)wglGetProcAddress("glTexParameteri");
    pglTexImage2D = glTexImage2D; // (glTexImage2DType)wglGetProcAddress("glTexImage2D");
    pglDeleteTextures = glDeleteTextures; // (glDeleteTexturesType)wglGetProcAddress("glDeleteTextures");

    if (!pglGenTextures|| !pglBindTexture|| !pglTexParameteri|| !pglTexImage2D || !pglDeleteTextures) {
        return 0;
    }
    wglMakeCurrent(0, 0);
    return 1;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_setSharedContext
        (JNIEnv *env, jclass clazz, jlong sharedContext, jint pixelType) {
    gSharedContext = (HGLRC)sharedContext;
    gPixelFormat = pixelType;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_initNative
        (JNIEnv *env, jclass clazz, jint textureType) {
    if (textureType != OPENGL_TEXTURE_TYPE) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "Unsupported texture type");
        return;
    }

    if (!initOpenGL()) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "Failed to init OpenGL");
        return;
    }

    gTextureType = textureType;
}

const char* gl_error_string(GLenum err) {
    switch (err) {
        case GL_NO_ERROR: return "GL_NO_ERROR";
        case GL_INVALID_ENUM: return "GL_INVALID_ENUM";
        case GL_INVALID_VALUE: return "GL_INVALID_VALUE";
        case GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
        case GL_STACK_OVERFLOW: return "GL_STACK_OVERFLOW";
        case GL_STACK_UNDERFLOW: return "GL_STACK_UNDERFLOW";
        case GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY";

        default: return "Unknown GL error";
    }
}

static void check_gl_error(JNIEnv *env, const char* msgPrefix) {
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        char msg[256];
        snprintf(msg, sizeof(msg), "%s: OpenGL error %d (%s)", msgPrefix, err, gl_error_string(err));
        jclass excCls = (*env)->FindClass(env, "java/lang/RuntimeException");
        (*env)->ThrowNew(env, excCls, msg);
    }
}

static void saveTextureToPng(const char* path, GLuint textureId, int width, int height) {
    pglBindTexture(GL_TEXTURE_2D, textureId);

    unsigned char* data = (unsigned char*)malloc(width * height * 4);
    if (!data) {
        printf("Failed to allocate buffer for texture readback\n");
        return;
    }

    glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);

    unsigned char* flipped = (unsigned char*)malloc(width * height * 4);
    if (!flipped) {
        printf("Failed to allocate buffer for vertical flip\n");
        free(data);
        return;
    }
    for (int y = 0; y < height; ++y) {
        memcpy(flipped + (height - 1 - y) * width * 4,
               data + y * width * 4,
               width * 4);
    }

    int res = stbi_write_png(path, width, height, 4, flipped, width * 4);
    if (!res) {
        printf("Failed to write PNG file: %s\n", path);
    }

    free(data);
    free(flipped);
}

JNIEXPORT jlong JNICALL Java_SharedTexturesTest_createTexture
        (JNIEnv *env, jclass clazz, jbyteArray byteArray, jint width, jint height) {
    if (!wglMakeCurrent(dc, gGLContext)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "SharedTexturesTest: can't make the context current");
        return 0;
    }

    if (gTextureType == -1) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "SharedTexturesTest: native is not initialized");
        return 0;
    }

    if (gTextureType != 2) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "Unsupported texture type");
        return 0;
    }

    jsize length = (*env)->GetArrayLength(env, byteArray);
    jbyte *pixels = (*env)->GetByteArrayElements(env, byteArray, NULL);

    GLuint texId = 0;
    pglGenTextures(1, &texId);
    check_gl_error(env, "pglGenTextures");
    pglBindTexture(GL_TEXTURE_2D, texId);
    check_gl_error(env, "pglBindTexture");


    pglTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    check_gl_error(env, "pglTexParameteri GL_TEXTURE_MAG_FILTER");

    pglTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    check_gl_error(env, "pglTexParameteri GL_TEXTURE_MIN_FILTER");

    #define GL_CLAMP_TO_EDGE 0x812F
    pglTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    pglTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    pglTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                  GL_RGBA, GL_UNSIGNED_BYTE, (const void*)pixels);

    check_gl_error(env, "glTexImage2D");

    (*env)->ReleaseByteArrayElements(env, byteArray, pixels, JNI_ABORT);
    pglBindTexture(GL_TEXTURE_2D, 0);

    saveTextureToPng("c:/develop/text.png", texId, width, height);

    return (jlong)texId;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_disposeTexture
        (JNIEnv *env, jclass clazz, jlong texture) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        GLuint texId = (GLuint)texture;
        pglDeleteTextures(1, &texId);\
    }
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_releaseContext
        (JNIEnv *env, jclass clazz) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        if (gGLContext) {
            wglMakeCurrent(0, 0);
            wglDeleteContext(gGLContext);
            gGLContext = 0;
        }

        if (dc)  {
            dc = 0;
            ReleaseDC(0, dc);
        }
    }
}