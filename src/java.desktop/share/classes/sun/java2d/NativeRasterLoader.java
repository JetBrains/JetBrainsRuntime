package sun.java2d;

public class NativeRasterLoader {
    /**
     * Loads native image raster into surface.
     *
     * @param pRaster native pointer image raster with 8-bit RGBA color components packed into integer pixels.
     * Note: The color data in this image is considered to be premultiplied with alpha.
     * @param width width of image in pixels
     * @param height height of image in pixels
     * @param pRects native pointer to array of "dirty" rects, each rect is a sequence of four 32-bit integers: x, y, width, heigth
     * Note: can be null (then whole image used)
     * @param rectsCount count of "dirty" rects (if 0 then whole image used)
     */
    public static void loadNativeRaster(Surface surface, long pRaster, int width, int height, long pRects, int rectsCount) {
        if (surface instanceof SurfaceData) {
            SurfaceData sd = (SurfaceData)surface;
            sd.loadNativeRaster(pRaster, width, height, pRects, rectsCount);
        }
    }
}