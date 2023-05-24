package sun.awt.wl;

import java.awt.EventQueue;
import java.awt.GraphicsDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import sun.awt.SunToolkit;
import sun.java2d.SunGraphicsEnvironment;
import sun.util.logging.PlatformLogger;
import sun.util.logging.PlatformLogger.Level;

public class WLGraphicsEnvironment extends SunGraphicsEnvironment {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLGraphicsEnvironment");

    private static boolean vkwlAvailable;

    static {
        vkwlAvailable = initVKWL();
        if (log.isLoggable(Level.FINE)) {
            log.fine("Vulkan rendering available: " + (vkwlAvailable?"YES":"NO"));
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
        synchronized (devices) {
            return devices.size();
        }
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screenNum) {
        synchronized (devices) {
            return devices.get(screenNum);
        }
    }

    @Override
    public boolean isDisplayLocal() {
        return true;
    }

    private final List<WLGraphicsDevice> devices = new ArrayList<>(5);

    private void notifyOutputConfigured(String name, int wlID, int x, int y, int width, int height,
                                        int subpixel, int transform, int scale) {
        // Called from native code whenever a new output appears or an existing one changes its properties
        // NB: initially called during WLToolkit.initIDs() on the main thread; later on EDT.
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Output configured id=%d at (%d, %d) %dx%d %dx scale", wlID, x, y, width, height, scale));
        }

        synchronized (devices) {
            boolean newOutput = true;
            for (int i = 0; i < devices.size(); i++) {
                final WLGraphicsDevice gd = devices.get(i);
                if (gd.getID() == wlID) {
                    newOutput = false;
                    if (gd.isSameDeviceAs(wlID, x, y)) {
                        gd.updateConfiguration(name, width, height, scale);
                    } else {
                        final WLGraphicsDevice updatedDevice = WLGraphicsDevice.createWithConfiguration(wlID, name, x, y, width, height, scale);
                        devices.set(i, updatedDevice);
                        gd.invalidate(updatedDevice);
                    }
                    break;
                }
            }
            if (newOutput) {
                final WLGraphicsDevice gd = WLGraphicsDevice.createWithConfiguration(wlID, name, x, y, width, height, scale);
                devices.add(gd);
            }
        }

        // Skip notification during the initial configuration events
        if (EventQueue.isDispatchThread()) {
            displayChanged();
        }
    }

    private WLGraphicsDevice getSimilarDevice(WLGraphicsDevice modelDevice) {
        WLGraphicsDevice similarDevice = devices.isEmpty() ? null : devices.getFirst();
        for (WLGraphicsDevice device : devices) {
            if (device.hasSameNameAs(modelDevice)) {
                similarDevice = device;
                break;
            } else if (device.hasSameSizeAs(modelDevice)) {
                similarDevice = device;
                break;
            }
        }

        return similarDevice;
    }

    private void notifyOutputDestroyed(int wlID) {
        // Called from native code whenever one of the outputs is no longer available.
        // All surfaces that were partly visible on that output should have
        // notifySurfaceLeftOutput().
        if (log.isLoggable(Level.FINE)) {
            log.fine(String.format("Output destroyed id=%d", wlID));
        }
        // NB: id may *not* be that of any output; if so, just ignore this event.
        synchronized (devices) {
            final Optional<WLGraphicsDevice> deviceOptional = devices.stream()
                    .filter(device -> device.getID() == wlID)
                    .findFirst();
            if (deviceOptional.isPresent()) {
                final WLGraphicsDevice destroyedDevice = deviceOptional.get();
                devices.remove(destroyedDevice);
                final WLGraphicsDevice similarDevice = getSimilarDevice(destroyedDevice);
                if (similarDevice != null) destroyedDevice.invalidate(similarDevice);
            }
        }

        displayChanged();
    }

    WLGraphicsDevice notifySurfaceEnteredOutput(WLComponentPeer wlComponentPeer, int wlOutputID) {
        synchronized (devices) {
            for (WLGraphicsDevice gd : devices) {
                if (gd.getID() == wlOutputID) {
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
                if (gd.getID() == wlOutputID) {
                    gd.removeWindow(wlComponentPeer);
                    return gd;
                }
            }
            return null;
        }
    }
}
