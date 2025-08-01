#include <jni.h>

#include "sun_java2d_opengl_GLXGraphicsConfigExt.h"

#include "GLXGraphicsConfig.h"

JNIEXPORT jlong JNICALL
Java_sun_java2d_opengl_GLXGraphicsConfigExt_getSharedContext
    (JNIEnv *env, jclass class)
{
#ifndef HEADLESS
    return ptr_to_jlong(GLXGC_GetSharedContext());
#else
    return 0;
#endif
}