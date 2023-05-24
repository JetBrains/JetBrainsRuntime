package sun.awt.wl;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.WritableRaster;

import sun.awt.image.OffScreenImage;
import sun.awt.image.SunVolatileImage;
import sun.awt.image.SurfaceManager;
import sun.awt.image.VolatileSurfaceManager;
import sun.java2d.SurfaceData;
import sun.java2d.loops.SurfaceType;
import sun.java2d.wl.WLSurfaceData;
import sun.java2d.wl.WLVolatileSurfaceManager;

public class WLGraphicsConfig extends GraphicsConfiguration
        implements SurfaceManager.Factory {
    private final WLGraphicsDevice device;
    private final int width;
    private final int height;
    private final int scale;

    public WLGraphicsConfig(WLGraphicsDevice device, int width, int height, int scale) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.scale = scale;
    }

    public boolean differsFrom(int width, int height, int scale) {
        return width != this.width || height != this.height || scale != this.scale;
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
        // TODO: may not be able to implement this fully, but we can try
        // obtaining physical width/height from wl_output.geometry event.
        // Those may be 0, of course.
        return getDefaultTransform();
    }

    @Override
    public Rectangle getBounds() {
        return new Rectangle(width, height);
    }

    public SurfaceType getSurfaceType() {
        return SurfaceType.IntArgb;
    }

    public SurfaceData createSurfaceData(WLComponentPeer peer) {
        return WLSurfaceData.createData(peer);
    }

    public int getScale() {
        return scale;
    }

    @Override
    public String toString() {
        return String.format("WLGraphicsConfig: %dx%d %dx scale", width, height, scale);
    }

    @Override
    public VolatileSurfaceManager createVolatileManager(SunVolatileImage image,
                                                        Object context) {
        return new WLVolatileSurfaceManager(image, context);
    }
}
