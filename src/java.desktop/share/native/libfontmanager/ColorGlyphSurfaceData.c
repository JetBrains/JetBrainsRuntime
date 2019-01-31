#include "jni_util.h"
#include "fontscalerdefs.h"
#include "SurfaceData.h"

typedef struct _GlyphOps {
    SurfaceDataOps sdOps;
    GlyphInfo*     glyph;
} GlyphOps;

static jint Glyph_Lock(JNIEnv *env,
                       SurfaceDataOps *ops,
                       SurfaceDataRasInfo *pRasInfo,
                       jint lockflags)
{
    SurfaceDataBounds bounds;
    GlyphInfo *glyph;
    if (lockflags & (SD_LOCK_WRITE | SD_LOCK_LUT | SD_LOCK_INVCOLOR | SD_LOCK_INVGRAY)) {
        JNU_ThrowInternalError(env, "Unsupported mode for glyph image surface");
        return SD_FAILURE;
    }
    glyph = ((GlyphOps*)ops)->glyph;
    bounds.x1 = 0;
    bounds.y1 = 0;
    bounds.x2 = glyph->width;
    bounds.y2 = glyph->height;
    SurfaceData_IntersectBounds(&pRasInfo->bounds, &bounds);
    return SD_SUCCESS;
}

static void Glyph_GetRasInfo(JNIEnv *env,
                             SurfaceDataOps *ops,
                             SurfaceDataRasInfo *pRasInfo)
{
    GlyphInfo *glyph = ((GlyphOps*)ops)->glyph;

    pRasInfo->rasBase = glyph->image;
    pRasInfo->pixelStride = 4;
    pRasInfo->scanStride = glyph->rowBytes;
    pRasInfo->pixelBitOffset = 0;
}

JNIEXPORT void JNICALL
Java_sun_font_ColorGlyphSurfaceData_initOps(JNIEnv *env, jobject sData)
{
    GlyphOps *ops = (GlyphOps*) SurfaceData_InitOps(env, sData, sizeof(GlyphOps));
    ops->sdOps.Lock = Glyph_Lock;
    ops->sdOps.GetRasInfo = Glyph_GetRasInfo;
}

JNIEXPORT void JNICALL
Java_sun_font_ColorGlyphSurfaceData_setCurrentGlyph(JNIEnv *env, jobject sData, jlong imgPtr)
{
    GlyphOps *ops = (GlyphOps*) SurfaceData_GetOpsNoSetup(env, sData);
    ops->glyph = (GlyphInfo*) jlong_to_ptr(imgPtr);
}