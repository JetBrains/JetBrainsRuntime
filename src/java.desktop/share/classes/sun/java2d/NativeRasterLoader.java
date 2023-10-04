package sun.java2d;

public class NativeRasterLoader {
    public static void loadNativeRaster(Surface surface, long pRaster, int width, int height, long pRects, int rectsCount) {
        if (surface instanceof SurfaceData) {
            SurfaceData sd = (SurfaceData)surface;
            sd.loadNativeRaster(pRaster, width, height, pRects, rectsCount);
        }
    }
}