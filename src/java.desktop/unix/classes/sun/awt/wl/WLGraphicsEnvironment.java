package sun.awt.wl;

import java.awt.GraphicsDevice;
import sun.java2d.SunGraphicsEnvironment;
import sun.java2d.SurfaceManagerFactory;
import sun.java2d.UnixSurfaceManagerFactory;
import sun.java2d.vulkan.VKGraphicsDevice;
import sun.util.logging.PlatformLogger;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLComponentPeer");
    private static boolean vulkanEnabled = false;
    private static native boolean initVK();

    static {
        System.loadLibrary("awt");
        SurfaceManagerFactory.setInstance(new UnixSurfaceManagerFactory());
        String prop = System.getProperty("sun.java2d.vulkan");
        if ("true".equals(prop)) {
            vulkanEnabled = initVK();
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
        if (vulkanEnabled) {
            return new VKGraphicsDevice();
        } else {
            return new WLGraphicsDevice();
        }
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }
}
