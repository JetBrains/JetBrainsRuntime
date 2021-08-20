package sun.awt.wl;

import java.awt.GraphicsDevice;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import sun.java2d.SunGraphicsEnvironment;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLGraphicsEnvironment");

    private static boolean vkwlAvailable;

    static {
        vkwlAvailable = initVKWL();
        if (log.isLoggable(Level.INFO)) {
            log.info("Vulkan rendering available: " + (vkwlAvailable?"YES":"NO"));
        }
    }

    private static native boolean initVKWL();

    private WLGraphicsEnvironment() {
    }

    private static class Holder {
        static final WLGraphicsEnvironment INSTANCE = new WLGraphicsEnvironment();
    }

    public static WLGraphicsEnvironment getSingleInstance() {
        return Holder.INSTANCE;
    }

    public static boolean isVKWLAvailable() {
        return vkwlAvailable;
    }

    @Override
    protected int getNumScreens() {
        try {
            outputHasBeenConfigured.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (devices) {
            return devices.size();
        }
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screenNum) {
        try {
            outputHasBeenConfigured.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (devices) {
            return devices.get(screenNum);
        }
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }

    private final CountDownLatch outputHasBeenConfigured = new CountDownLatch(1);
    private final List<WLGraphicsDevice> devices = new ArrayList<>(5);

    private void notifyOutputConfigured(String name, int wlID, int x, int y, int width, int height,
                                        int subpixel, int transform, int scale) {
        // Called from native code whenever a new output appears or an existing one changes its properties
        synchronized (devices) {
            boolean newOutput = true;
            for (final WLGraphicsDevice gd : devices) {
                if (gd.getWLID() == wlID) {
                    gd.updateConfiguration(name, x, y, width, height, scale);
                    newOutput = false;
                }
            }
            if (newOutput) {
                final WLGraphicsDevice gd = new WLGraphicsDevice(wlID);
                gd.updateConfiguration(name, x, y, width, height, scale);
                devices.add(gd);
                outputHasBeenConfigured.countDown();
            }
        }
        displayChanged();
    }

    private void notifyOutputDestroyed(int wlID) {
        // Called from native code whenever one of the outputs is no longer available.
        // All surfaces that were partly visible on that output should have
        // notifySurfaceLeftOutput().

        // NB: id may *not* be that of any output; if so, just ignore this event.
        synchronized (devices) {
            devices.removeIf(gd -> gd.getWLID() == wlID);
        }
    }

    WLGraphicsDevice notifySurfaceEnteredOutput(WLComponentPeer wlComponentPeer, int wlOutputID) {
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getWLID() == wlOutputID) {
                    gd.addWindow(wlComponentPeer);
                    return gd;
                }
            }
            return null;
        }
    }

    WLGraphicsDevice notifySurfaceLeftOutput(WLComponentPeer wlComponentPeer, int wlOutputID) {
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getWLID() == wlOutputID) {
                    gd.removeWindow(wlComponentPeer);
                    return gd;
                }
            }
            return null;
        }
    }
}
