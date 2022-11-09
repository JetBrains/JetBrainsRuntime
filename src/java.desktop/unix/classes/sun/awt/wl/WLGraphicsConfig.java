package sun.awt.wl;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.WritableRaster;

import sun.awt.image.OffScreenImage;
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
        return switch (transparency) {
            case Transparency.OPAQUE -> getColorModel();
            case Transparency.BITMASK -> new DirectColorModel(25, 0xff0000, 0xff00, 0xff, 0x1000000);
            case Transparency.TRANSLUCENT -> new DirectColorModel(32, 0xff0000, 0xff00, 0xff, 0xff000000);
            default -> null;
        };
    }

    public Image createAcceleratedImage(Component target,
                                        int width, int height)
    {
        ColorModel model = getColorModel(Transparency.OPAQUE);
        WritableRaster raster = model.createCompatibleWritableRaster(width, height);
        return new OffScreenImage(target, model, raster, model.isAlphaPremultiplied());
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
