package sun.awt.wl;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.ColorModel;

public class WLGraphicsConfig extends GraphicsConfiguration {
    private final WLGraphicsDevice device;
    private final Rectangle bounds = new Rectangle(800, 600);

    public WLGraphicsConfig(WLGraphicsDevice device) {
        this.device = device;
    }

    @Override
    public GraphicsDevice getDevice() {
        return device;
    }

    @Override
    public ColorModel getColorModel() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ColorModel getColorModel(int transparency) {
        throw new UnsupportedOperationException();
    }

    @Override
    public AffineTransform getDefaultTransform() {
        throw new UnsupportedOperationException();
    }

    @Override
    public AffineTransform getNormalizingTransform() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rectangle getBounds() {
        return bounds;
    }
}
