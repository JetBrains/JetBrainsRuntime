package sun.awt.wl;

import java.awt.GraphicsDevice;
import sun.java2d.SunGraphicsEnvironment;
import sun.java2d.SurfaceManagerFactory;
import sun.java2d.UnixSurfaceManagerFactory;
import sun.java2d.vulkan.VKGraphicsDevice;
import sun.util.logging.PlatformLogger;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    private static boolean vulkanRequested = false;
    static {
        SurfaceManagerFactory.setInstance(new UnixSurfaceManagerFactory());
        String prop = System.getProperty("sun.java2d.vulkan");
        if ("true".equals(prop)) {
            vulkanRequested = true;
        }
    }

    @Override
    protected int getNumScreens() {
        log.info("Not implemented: WLGraphicsEnvironment.getNumScreens()");
        return 1;
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screennum) {
        log.info("Not implemented: WLGraphicsEnvironment.makeScreenDevice(int)");
        if (vulkanRequested) {
            return new VKGraphicsDevice(0);
        } else {
            return new WLGraphicsDevice();
        }
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }
}
