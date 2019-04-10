#include "MTLUtils.h"

float MTLUtils_normalizeX(id<MTLTexture> dest, float x) { return (2.0*x/dest.width) - 1.0; }
float MTLUtils_normalizeY(id<MTLTexture> dest, float y) { return 2.0*(1.0 - y/dest.height) - 1.0; }
