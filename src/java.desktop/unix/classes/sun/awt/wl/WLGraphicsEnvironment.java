package sun.awt.wl;

import java.awt.GraphicsDevice;
import sun.java2d.SunGraphicsEnvironment;
import sun.java2d.SurfaceManagerFactory;
import sun.java2d.UnixSurfaceManagerFactory;
import sun.util.logging.PlatformLogger;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    static {
        SurfaceManagerFactory.setInstance(new UnixSurfaceManagerFactory());
    }

    @Override
    protected int getNumScreens() {
        log.info("Not implemented: WLGraphicsEnvironment.getNumScreens()");
        return 1;
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screennum) {
        log.info("Not implemented: WLGraphicsEnvironment.makeScreenDevice(int)");
        return new WLGraphicsDevice();
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }
}
