#include <jni.h>

#include "GLXGraphicsConfig.h"

JNIEXPORT jint JNICALL Java_sun_java2d_opengl_OGLGraphicsConfigJbrApi_getPixelFormat
        (JNIEnv *env, jclass class, jlong pConfigInfo) {
    return 0;
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_opengl_OGLGraphicsConfigJbrApi_getSharedContext
        (JNIEnv *env, jclass class, jlong pConfigInfo) {
#ifndef HEADLESS
    return ptr_to_jlong(GLXGC_GetSharedContext());
#else
    return 0;
#endif
}