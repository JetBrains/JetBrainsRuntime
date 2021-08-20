package sun.java2d.wl;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.ColorModel;
import java.awt.image.Raster;

import sun.awt.image.SunVolatileImage;
import sun.awt.wl.WLComponentPeer;
import sun.awt.wl.WLGraphicsConfig;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.util.logging.PlatformLogger;

public class WLSurfaceData extends SurfaceData {

    private static final PlatformLogger log = PlatformLogger.getLogger("sun.java2d.wl.WLSurfaceData");
    private final WLComponentPeer peer;
    private final WLGraphicsConfig graphicsConfig;
    private final int depth;

    public native void assignSurface(long surfacePtr);

    protected native void initOps(int width, int height, int backgroundRGB);

    protected WLSurfaceData(WLComponentPeer peer,
                            WLGraphicsConfig gc,
                            SurfaceType sType,
                            ColorModel cm) {
        super(sType, cm);
        this.peer = peer;
        this.graphicsConfig = gc;
        this.depth = cm.getPixelSize();
        final int backgroundRGB = peer.getBackground() != null
                ? peer.getBackground().getRGB()
                : 0;
        initOps(peer.getBufferWidth(), peer.getBufferHeight(), backgroundRGB);
    }

    /**
     * Method for instantiating a Window SurfaceData
     */
    public static WLSurfaceData createData(WLComponentPeer peer) {
        WLGraphicsConfig gc = getGC(peer);
        return new WLSurfaceData(peer, gc, gc.getSurfaceType(), peer.getColorModel());
    }

    public static WLGraphicsConfig getGC(WLComponentPeer peer) {
        if (peer != null) {
            return (WLGraphicsConfig) peer.getGraphicsConfiguration();
        } else {
            GraphicsEnvironment env =
                    GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice gd = env.getDefaultScreenDevice();
            return (WLGraphicsConfig) gd.getDefaultConfiguration();
        }
    }

    @Override
    public SurfaceData getReplacement() {
        return null;
    }

    @Override
    public GraphicsConfiguration getDeviceConfiguration() {
        return graphicsConfig;
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

    public static boolean isAccelerationEnabled() {
        return false;
    }

    public static SurfaceData createData(WLGraphicsConfig gc, int width, int height, ColorModel cm,
                                         SunVolatileImage vImg, long drawable, int opaque,
                                         boolean b) {
        throw new UnsupportedOperationException("SurfaceData not associated with a Component is not supported");
    }

    public native void revalidate(int width, int height);

    public native void commitToServer();
}
