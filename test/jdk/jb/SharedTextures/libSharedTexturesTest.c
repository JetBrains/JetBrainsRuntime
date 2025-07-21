#include <jni.h>

#include <GL/gl.h>
#include <stdlib.h>

#ifdef _WIN32
#include <windows.h>
#endif

#ifdef LINUX
#include <X11/Xlib.h>
#include <GL/glx.h>
#endif

static int gTextureType = -1;

static const int METAL_TEXTURE_TYPE = 1;
static const int OPENGL_TEXTURE_TYPE = 2;

static const char* gl_error_string(GLenum err) {
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

#ifdef _WIN32
static HGLRC gSharedContext = 0;
static int gPixelFormat = 0;

static HDC dc = 0;
static HGLRC gGLContext = 0;

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
    glGenTextures(1, &texId);
    check_gl_error(env, "glGenTextures");
    glBindTexture(GL_TEXTURE_2D, texId);
    check_gl_error(env, "glBindTexture");


    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    check_gl_error(env, "glTexParameteri GL_TEXTURE_MAG_FILTER");

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    check_gl_error(env, "glTexParameteri GL_TEXTURE_MIN_FILTER");

    #define GL_CLAMP_TO_EDGE 0x812F
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                  GL_RGBA, GL_UNSIGNED_BYTE, (const void*)pixels);

    check_gl_error(env, "glTexImage2D");

    (*env)->ReleaseByteArrayElements(env, byteArray, pixels, JNI_ABORT);
    glBindTexture(GL_TEXTURE_2D, 0);
    glFinish();

    return (jlong)texId;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_disposeTexture
        (JNIEnv *env, jclass clazz, jlong texture) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        GLuint texId = (GLuint)texture;
        glDeleteTextures(1, &texId);\
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

#endif

#ifdef LINUX
#include <X11/Xlib.h>
#include <GL/glx.h>
#include <GL/gl.h>

static Display *display = NULL;
static GLXContext gGLContext = 0;
static GLXContext gSharedContext = 0;
static GLXDrawable gDrawable = 0;
static GLXFBConfig gFBConfig;
static XVisualInfo *gVisualInfo = NULL;

static int initOpenGL() {
    display = XOpenDisplay(NULL);
    if (!display) {
        return 0;
    }

    int visual_attribs[] = {
        GLX_RGBA,
        GLX_DOUBLEBUFFER,
        GLX_RED_SIZE, 8,
        GLX_GREEN_SIZE, 8,
        GLX_BLUE_SIZE, 8,
        GLX_ALPHA_SIZE, 8,
        GLX_DEPTH_SIZE, 24,
        None
    };

    gVisualInfo = glXChooseVisual(display, DefaultScreen(display), visual_attribs);
    if (!gVisualInfo) {
        XCloseDisplay(display);
        display = NULL;
        return 0;
    }

    gGLContext = glXCreateContext(display, gVisualInfo, gSharedContext, /* direct = */ GL_TRUE);
    if (!gGLContext) {
        XFree(gVisualInfo);
        gVisualInfo = NULL;
        XCloseDisplay(display);
        display = NULL;
        return 0;
    }

    Window root = RootWindow(display, DefaultScreen(display));
    XSetWindowAttributes swa;
    swa.colormap = XCreateColormap(display, root, gVisualInfo->visual, AllocNone);
    swa.border_pixel = 0;
    swa.event_mask = StructureNotifyMask;

    Window win = XCreateWindow(display, root, 0, 0, 1, 1, 0,
                             gVisualInfo->depth, InputOutput,
                             gVisualInfo->visual,
                             CWBorderPixel | CWColormap | CWEventMask, &swa);

    gDrawable = win;

    if (!glXMakeCurrent(display, gDrawable, gGLContext)) {
        glXDestroyContext(display, gGLContext);
        gGLContext = 0;
        XDestroyWindow(display, win);
        XFree(gVisualInfo);
        gVisualInfo = NULL;
        XCloseDisplay(display);
        display = NULL;
        return 0;
    }

    glXMakeCurrent(display, None, NULL);
    return 1;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_setSharedContext
        (JNIEnv *env, jclass clazz, jlong sharedContext, jint pixelType) {
    gSharedContext = (GLXContext)sharedContext;
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

JNIEXPORT jlong JNICALL Java_SharedTexturesTest_createTexture
        (JNIEnv *env, jclass clazz, jbyteArray byteArray, jint width, jint height) {
    if (!glXMakeCurrent(display, gDrawable, gGLContext)) {
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

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, (const void*)pixels);
    check_gl_error(env, "glTexImage2D");

    (*env)->ReleaseByteArrayElements(env, byteArray, pixels, JNI_ABORT);
    glBindTexture(GL_TEXTURE_2D, 0);
    glFinish();
        check_gl_error(env, "glFinish");

    glXMakeCurrent(display, None, NULL);

    return (jlong)texId;
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_disposeTexture
        (JNIEnv *env, jclass clazz, jlong texture) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        GLuint texId = (GLuint)texture;
        glDeleteTextures(1, &texId);
    }
}

JNIEXPORT void JNICALL Java_SharedTexturesTest_releaseContext
        (JNIEnv *env, jclass clazz) {
    if (gTextureType == OPENGL_TEXTURE_TYPE) {
        if (gGLContext) {
            glXMakeCurrent(display, None, NULL);
            glXDestroyContext(display, gGLContext);
            gGLContext = 0;
        }

        if (gDrawable) {
            XDestroyWindow(display, gDrawable);
            gDrawable = 0;
        }

        if (gVisualInfo) {
            XFree(gVisualInfo);
            gVisualInfo = NULL;
        }

        if (display) {
            XCloseDisplay(display);
            display = NULL;
        }
    }
}

#endif
