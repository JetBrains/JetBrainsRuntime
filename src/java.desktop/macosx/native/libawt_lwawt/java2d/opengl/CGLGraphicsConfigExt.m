#import "sun_java2d_opengl_CGLGraphicsConfigExt.h"

#import "JNIUtilities.h"

extern NSOpenGLContext *sharedContext;
extern NSOpenGLPixelFormat *sharedPixelFormat;

JNIEXPORT jlong JNICALL
Java_sun_java2d_opengl_CGLGraphicsConfigExt_getSharedContext
    (JNIEnv *env, jclass cls)
{
  return ptr_to_jlong(sharedContext.CGLContextObj) ;
}

JNIEXPORT jlong JNICALL
Java_sun_java2d_opengl_CGLGraphicsConfigExt_getPixelFormat
    (JNIEnv *env, jclass cls)
{
  return ptr_to_jlong(sharedPixelFormat.CGLPixelFormatObj);
}
