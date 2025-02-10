package sun.java2d.metal;

public class MTLSurfaceDataExt {
    public static boolean initWithTexture(MTLSurfaceData sd, long texturePtr) {
        return initWithTexture(sd.getNativeOps(), false, texturePtr);
    }

    private static native boolean initWithTexture(long pData, boolean isOpaque, long texturePtr);
}
