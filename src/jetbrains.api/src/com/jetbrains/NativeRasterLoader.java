package com.jetbrains;

import java.awt.image.VolatileImage;

public interface NativeRasterLoader {
    void loadNativeRaster(VolatileImage vi, long pRaster, int width, int height, long pRects, int rectsCount);
}