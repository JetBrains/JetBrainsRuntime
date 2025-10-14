#include <jni.h>
#include <stdlib.h>

#ifdef _WIN32
#include <windows.h>
#endif

#include <GL/gl.h>

#ifdef LINUX
#include <X11/Xlib.h>
#include <GL/glx.h>
#include <X11/Xlib.h>
#endif

static int gTextureType = -1;

static const int METAL_TEXTURE_TYPE = 1;
static const int OPENGL_TEXTURE_TYPE = 2;

static void check_gl_error(JNIEnv *env, const char* msgPrefix) {
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        char msg[256];
        snprintf(msg, sizeof(msg), "%s: OpenGL error %d", msgPrefix, err);
        jclass excCls = (*env)->FindClass(env, "java/lang/RuntimeException");
        (*env)->ThrowNew(env, excCls, msg);
    }
}

#ifdef _WIN32
static HWND gHwnd = 0;
static HGLRC gSharedContext = 0;
static int gPixelFormat = 0;

static HDC dc = 0;
static HGLRC gGLContext = 0;

static int initOpenGL() {
    gHwnd = CreateWindowEx(0, "STATIC", "Hidden", WS_POPUP, 0,0,1,1, NULL, NULL, GetModuleHandle(NULL), NULL);
    if (gHwnd == 0) {
        return FALSE;
    }

    dc = GetDC(gHwnd);
    if (!dc) {
        DestroyWindow(gHwnd);
        return FALSE;
    }

    if (!SetPixelFormat(dc, gPixelFormat, 0)) {
        ReleaseDC(0, dc);
        DestroyWindow(gHwnd);
        return FALSE;
    }

    gGLContext = wglCreateContext(dc);
    if (!gGLContext) {
        ReleaseDC(0, dc);
        DestroyWindow(gHwnd);
        return FALSE;
    }

    if (!wglShareLists(gSharedContext, gGLContext)) {
        ReleaseDC(0, dc);
        DestroyWindow(gHwnd);
        return FALSE;
    }

    if (!wglMakeCurrent(dc, gGLContext)) {
        wglDeleteContext(gGLContext);
        gGLContext = 0;
        ReleaseDC(0, dc);
        DestroyWindow(gHwnd);
        return FALSE;
    }

    wglMakeCurrent(0, 0);
    return 1;
}

static int makeContextCurrent(int isCurrent) {
    if (isCurrent) {
        return wglMakeCurrent(dc, gGLContext);
    }
    return wglMakeCurrent(0, 0);
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
    gSharedContext = (HGLRC)contextInfo[0];
    gPixelFormat = (int)contextInfo[1];
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
            ReleaseDC(0, dc);
            DestroyWindow(gHwnd);
        }
    }
}

#endif

#ifdef LINUX

static Display *gDisplay = NULL;
static GLXContext gSharedContext = 0;
static GLXFBConfig gFBConfig = 0;

static GLXPbuffer gPbuffer = 0;
static GLXContext gGLContext = 0;

static int initOpenGL() {
    int pbAttribs[] = {
        GLX_PBUFFER_WIDTH,  1000,
        GLX_PBUFFER_HEIGHT, 1000,
        None
    };

    gPbuffer = glXCreatePbuffer(gDisplay, gFBConfig, pbAttribs);
    gGLContext = glXCreateNewContext(gDisplay, gFBConfig, GLX_RGBA_TYPE, gSharedContext, /* direct = */ GL_TRUE);

    if (!gGLContext) {
        printf("Failed to glXCreateNewContext: %d\n", glGetError());
        return 0;
    }

    if (!glXMakeCurrent(gDisplay, gPbuffer, gGLContext)) {
        printf("Failed to glXMakeCurrent: %d\n", glGetError());
        glXDestroyContext(gDisplay, gGLContext);
        gGLContext = 0;
        return 0;
    }

    glXMakeCurrent(gDisplay, None, NULL);
    return 1;
}

static int makeContextCurrent(int isCurrent) {
    if (isCurrent) {
        return glXMakeCurrent(gDisplay, gPbuffer, gGLContext);
    }
    return glXMakeCurrent(gDisplay, None, NULL);
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_setSharedContextInfo
        (JNIEnv *env, jclass clazz, jlongArray sharedContextInfo) {
    jsize length = (*env)->GetArrayLength(env, sharedContextInfo);
    if (length != 3) {
            (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"),
            "Unexpected shared context info size");
    }

    jlong contextInfo[3];
    (*env)->GetLongArrayRegion(env, sharedContextInfo, 0, length, contextInfo);

    gSharedContext = (GLXContext)contextInfo[0];
    gDisplay = (Display*)contextInfo[1];
    gFBConfig = (GLXFBConfig)contextInfo[2];
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_releaseContext
        (JNIEnv *env, jclass clazz) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        if (gGLContext) {
            glXMakeCurrent(gDisplay, None, NULL);
            glXDestroyContext(gDisplay, gGLContext);
            gGLContext = 0;
        }

        if (gPbuffer) {
            glXDestroyPbuffer(gDisplay, gPbuffer);
            gPbuffer = 0;
        }
    }
}

#endif

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

JNIEXPORT jlong JNICALL Java_SharedTexturesTest_createTexture
        (JNIEnv *env, jclass clazz, jbyteArray byteArray, jint width, jint height) {
    if (!makeContextCurrent(1)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "SharedTexturesTest: can't make the context current");
        return 0;
    }

    if (gTextureType == -1) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "SharedTexturesTest: native is not initialized");
        return 0;
    }

    if (gTextureType != OPENGL_TEXTURE_TYPE) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
            "Unsupported texture type");
        return 0;
    }

    jsize length = (*env)->GetArrayLength(env, byteArray);
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

    #ifndef GL_CLAMP_TO_EDGE
    #define GL_CLAMP_TO_EDGE 0x812F
    #endif

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, (const void*)pixels);
    check_gl_error(env, "glTexImage2D");

    (*env)->ReleaseByteArrayElements(env, byteArray, pixels, JNI_ABORT);
    glBindTexture(GL_TEXTURE_2D, 0);
    glFinish();
    check_gl_error(env, "glFinish");

    makeContextCurrent(0);

    return (jlong)texId;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_disposeTexture
        (JNIEnv *env, jclass clazz, jlong texture) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        GLuint texId = (GLuint)texture;
        glDeleteTextures(1, &texId);\
    }
}
