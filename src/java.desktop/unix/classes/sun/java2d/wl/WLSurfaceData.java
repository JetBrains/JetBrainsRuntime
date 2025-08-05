package sun.java2d.wl;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.util.Objects;

import sun.awt.image.SunVolatileImage;
import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLGraphicsConfig;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.util.logging.PlatformLogger;

public class WLSurfaceData extends SurfaceData implements WLSurfaceDataExt {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.wl.WLSurfaceData");
    private final WLComponentPeer peer;

    public native void assignSurface(long surfacePtr);

    protected native void initOps(int width, int height, int scale, int backgroundRGB);

    protected WLSurfaceData(WLComponentPeer peer) {
        super(((WLGraphicsConfig)peer.getGraphicsConfiguration()).getSurfaceType(),
                peer.getGraphicsConfiguration().getColorModel());
        this.peer = peer;
        final int backgroundRGB = peer.getBackground() != null
                ? peer.getBackground().getRGB()
                : 0;
        final int scale = ((WLGraphicsConfig)peer.getGraphicsConfiguration()).getScale();
        initOps(peer.getBufferWidth(), peer.getBufferHeight(), scale, backgroundRGB);
    }

    /**
     * Method for instantiating a Window SurfaceData
     */
    public static WLSurfaceData createData(WLComponentPeer peer) {
        if (peer == null) throw new UnsupportedOperationException("Surface without the corresponding window peer is not supported");

        return new WLSurfaceData(peer);
    }

    @Override
    public SurfaceData getReplacement() {
        return null;
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return peer.getGraphicsConfiguration();
    }

    @Override
    public Raster getRaster(int x, int y, int w, int h) {
        return null;
    }

    @Override
    public Rectangle getBounds() {
        Rectangle r = peer.getBufferBounds();
        r.x = r.y = 0;
        return r;
    }

    @Override
    public Object getDestination() {
        return peer.getTarget();
    }

    public static SurfaceData createData(WLGraphicsConfig gc, int width, int height, ColorModel cm,
                                         SunVolatileImage vImg, long drawable, int opaque,
                                         boolean b) {
        throw new UnsupportedOperationException("SurfaceData not associated with a Component is not supported");
    }

    public native void revalidate(int width, int height, int scale);

    @Override
    public native void flush();
}
