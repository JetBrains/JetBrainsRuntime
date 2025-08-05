package sun.java2d.wl;

public interface WLSurfaceDataExt {

    void assignSurface(long surfacePtr);
    void revalidate(int width, int height, int scale);
}
