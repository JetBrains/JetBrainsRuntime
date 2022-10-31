package sun.awt.wl;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.wl.WLSurfaceData;

public class WLGraphicsConfig extends GraphicsConfiguration {
    private final WLGraphicsDevice device;
    private final Rectangle bounds = new Rectangle(800, 600);

    public WLGraphicsConfig(WLGraphicsDevice device) {
        this.device = device;
    }

    @Override
    public WLGraphicsDevice getDevice() {
        return device;
    }

    @Override
    public ColorModel getColorModel() {
        return new DirectColorModel(24, 0xff0000, 0xff00, 0xff);
    }

    @Override
    public ColorModel getColorModel(int transparency) {
        switch (transparency) {
            case Transparency.OPAQUE:
                return getColorModel();
            case Transparency.BITMASK:
                throw new UnsupportedOperationException("Transparency BITMASK not supported");
            case Transparency.TRANSLUCENT:
                throw new UnsupportedOperationException("Transparency TRANSLUCENT not supported");
            default:
                return null;
        }
    }

    @Override
    public AffineTransform getDefaultTransform() {
        double scale = getScale();
        return AffineTransform.getScaleInstance(scale, scale);
    }

    @Override
    public AffineTransform getNormalizingTransform() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }

    public SurfaceType getSurfaceType() {
        return SurfaceType.IntArgb;
    }

    public SurfaceData createSurfaceData(WLComponentPeer peer) {
        return WLSurfaceData.createData(peer);
    }

    public double getScale() {
        return getDevice().getScaleFactor();
    }
}
