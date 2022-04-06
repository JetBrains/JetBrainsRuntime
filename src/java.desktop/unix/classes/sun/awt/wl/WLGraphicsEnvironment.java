package sun.awt.wl;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.image.BufferedImage;
import java.util.Locale;
import sun.java2d.SurfaceManagerFactory;
import sun.java2d.UnixSurfaceManagerFactory;

public class WLGraphicsEnvironment extends GraphicsEnvironment {
    private final WLGraphicsDevice device = new WLGraphicsDevice();

    static {
        SurfaceManagerFactory.setInstance(new UnixSurfaceManagerFactory());
    }

    @Override
    public GraphicsDevice[] getScreenDevices() throws HeadlessException {
        return new GraphicsDevice[] {device};
    }

    @Override
    public GraphicsDevice getDefaultScreenDevice() throws HeadlessException {
        return device;
    }

    @Override
    public Graphics2D createGraphics(BufferedImage img) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Font[] getAllFonts() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getAvailableFontFamilyNames() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getAvailableFontFamilyNames(Locale l) {
        throw new UnsupportedOperationException();
    }
}
