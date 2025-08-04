#include <jni.h>

#include "sun_java2d_opengl_GLXGraphicsConfigExt.h"

#include "GLXGraphicsConfig.h"

#ifndef HEADLESS
#include <X11/Xlib.h>
extern Display *awt_display;
#endif /* !HEADLESS */

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

JNIEXPORT jlong JNICALL Java_sun_java2d_opengl_GLXGraphicsConfigExt_getAwtDisplay
        (JNIEnv *env, jclass class) {
#ifndef HEADLESS
    return ptr_to_jlong(awt_display);
#else
    return 0;
#endif
}

JNIEXPORT jlong JNICALL Java_sun_java2d_opengl_GLXGraphicsConfigExt_getFBConfig
    (JNIEnv *env, jclass class, jlong pGlxNativeConfig)
{
#ifndef HEADLESS
    if (!pGlxNativeConfig) {
        J2dTraceLn(J2D_TRACE_ERROR, "GLXGraphicsConfigExt_getPixelFormat: pGlxNativeConfig is nullptr");
    }

    GLXGraphicsConfigInfo *glxinfo = jlong_to_ptr(pGlxNativeConfig);
    return ptr_to_jlong(glxinfo->fbconfig);
#else
    return 0;
#endif
}