package com.jetbrains.desktop;

import sun.awt.image.SunVolatileImage;

import java.awt.image.VolatileImage;

class NativeRasterLoader {
    static void loadNativeRaster(VolatileImage vi, long pRaster, int width, int height, long pRects, int rectsCount) {
        if (!(vi instanceof SunVolatileImage)) {
            System.err.printf("Unsupported type of VolatileImage: %s\n", vi);
            return;
        }

        SunVolatileImage svi = (SunVolatileImage)vi;
        sun.java2d.NativeRasterLoader.loadNativeRaster(svi.getDestSurface(), pRaster, width, height, pRects, rectsCount);
    }
}