package sun.awt.wl;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;

public class WLGraphicsDevice extends GraphicsDevice {
    private double scale = 1.0;

    private final WLGraphicsConfig config = new WLGraphicsConfig(this);

    @Override
    public int getType() {
        return TYPE_RASTER_SCREEN;
    }

    @Override
    public String getIDstring() {
        return "WLGraphicsDevice";
    }

    @Override
    public GraphicsConfiguration[] getConfigurations() {
        return new GraphicsConfiguration[] {config};
    }

    @Override
    public GraphicsConfiguration getDefaultConfiguration() {
        return config;
    }

    public double getScaleFactor() {
        return scale;
    }
}
