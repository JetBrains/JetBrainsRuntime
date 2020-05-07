#ifndef __RENDEROPTIONS_H
#define __RENDEROPTIONS_H

#include <jni.h>
#include "MTLSurfaceDataBase.h"

// Utility struct to transfer rendering paramenters
typedef struct {
    jboolean isTexture;
    jboolean isAA;
    int interpolation;
    SurfaceRasterFlags srcFlags;
    SurfaceRasterFlags dstFlags;
} RenderOptions;


#endif //__RENDEROPTIONS_H
