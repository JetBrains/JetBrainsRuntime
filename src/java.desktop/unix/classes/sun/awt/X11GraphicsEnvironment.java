/*
 * Copyright (c) 1997, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.awt;

import java.awt.AWTError;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import sun.awt.X11.XToolkit;
import sun.java2d.SunGraphicsEnvironment;
import sun.java2d.xr.XRSurfaceData;

/**
 * This is an implementation of a GraphicsEnvironment object for the
 * default local GraphicsEnvironment used by the Java Runtime Environment
 * for X11 environments.
 *
 * @see GraphicsDevice
 * @see java.awt.GraphicsConfiguration
 */
public final class X11GraphicsEnvironment extends SunGraphicsEnvironment implements HiDPIInfoProvider {

    static {
        initStatic();
    }

    @SuppressWarnings("restricted")
    private static void initStatic() {
        System.loadLibrary("awt");

        /*
         * Note: The XToolkit object depends on the static initializer
         * of X11GraphicsEnvironment to initialize the connection to
         * the X11 server.
         */
        if (!isHeadless()) {
            // first check the OGL system property
            boolean glxRequested = false;
            boolean glxRecommended = false;
            String prop = System.getProperty("sun.java2d.opengl");
            if (prop != null) {
                if (prop.equals("true") || prop.equals("t")) {
                    glxRequested = true;
                } else if (prop.equals("True") || prop.equals("T")) {
                    glxRequested = true;
                    glxVerbose = true;
                }
            } else if (openGLRecommended()) {
                glxRequested = true;
                glxRecommended = true;
            }

            // Now check for XRender system property
            boolean xRenderRequested = true;
            boolean xRenderIgnoreLinuxVersion = false;
            String xProp = System.getProperty("sun.java2d.xrender");
            if (xProp != null) {
                if (xProp.equals("false") || xProp.equals("f")) {
                    xRenderRequested = false;
                } else if (xProp.equals("True") || xProp.equals("T")) {
                    xRenderRequested = true;
                    xRenderVerbose = true;
                }

                if(xProp.equalsIgnoreCase("t") || xProp.equalsIgnoreCase("true")) {
                    xRenderIgnoreLinuxVersion = true;
                }
            }

            // initialize the X11 display connection
            initDisplay(glxRequested);

            // only attempt to initialize GLX if it was requested
            if (glxRequested) {
                glxAvailable = initGLX(glxRecommended);
                if (glxVerbose && !glxAvailable) {
                    System.out.println(
                            "Could not enable OpenGL " +
                            "pipeline (GLX 1.3 not available)");
                }
            }

            // only attempt to initialize Xrender if it was requested
            if (xRenderRequested) {
                xRenderAvailable = initXRender(xRenderVerbose, xRenderIgnoreLinuxVersion);
                if (xRenderVerbose && !xRenderAvailable) {
                    System.out.println(
                            "Could not enable XRender pipeline");
                }
            }

            if (xRenderAvailable) {
                XRSurfaceData.initXRSurfaceData();
            }
        }
    }

    private static boolean isVMWare() {
        final String virtName = System.getProperty("jbr.virtualization.information");
        return virtName != null && virtName.equals("VMWare virtualization");
    }

    private static boolean openGLRecommended() {
        final String sessionType = System.getenv("XDG_SESSION_TYPE");
        return (sessionType != null && sessionType.equals("wayland") && isVMWare());
    }


    private static boolean glxAvailable;
    private static boolean glxVerbose;

    private static native boolean initGLX(boolean glxRecommended);

    public static boolean isGLXAvailable() {
        return glxAvailable;
    }

    public static boolean isGLXVerbose() {
        return glxVerbose;
    }

    private static boolean xRenderVerbose;
    private static boolean xRenderAvailable;

    private static native boolean initXRender(boolean verbose, boolean ignoreLinuxVersion);
    public static boolean isXRenderAvailable() {
        return xRenderAvailable;
    }

    public static boolean isXRenderVerbose() {
        return xRenderVerbose;
    }

    /**
     * Checks if Shared Memory extension can be used.
     * Returns:
     *   -1 if server doesn't support MITShm
     *    1 if server supports it and it can be used
     *    0 otherwise
     */
    private static native int checkShmExt();

    private static  native String getDisplayString();
    private Boolean isDisplayLocal;

    /** Available X11 screens. */
    private final Map<Integer, X11GraphicsDevice> devices = new HashMap<>(5);

    /**
     * The key in the {@link #devices} for the main screen.
     */
    private int mainScreen;

    // list of invalidated graphics devices (those which were removed)
    private List<WeakReference<X11GraphicsDevice>> oldDevices = new ArrayList<>();

    /**
     * This should only be called from the static initializer, so no need for
     * the synchronized keyword.
     */
    private static native void initDisplay(boolean glxRequested);

    @Override
    protected native int getNumScreens();

    private native int getDefaultScreenNum();

    public X11GraphicsEnvironment() {
        if (isHeadless()) {
            return;
        }

        /* Populate the device table */
        rebuildDevices();
    }

    /**
     * Initialize the native list of devices.
     */
    private static native void initNativeData();

    /**
     * Updates the list of devices and notify listeners.
     */
    public void rebuildDevices() {
        XToolkit.awtLock();
        try {
            initNativeData();
            initDevices();
        } finally {
            XToolkit.awtUnlock();
        }
        displayChanged();
    }

    /**
     * (Re)create all X11GraphicsDevices, reuses a devices if it is possible.
     */
    private synchronized void initDevices() {
        Map<Integer, X11GraphicsDevice> old = new HashMap<>(devices);
        devices.clear();

        int numScreens = getNumScreens();
        if (numScreens == 0) {
            throw new AWTError("no screen devices");
        }
        int index = getDefaultScreenNum();
        mainScreen = 0 < index && index < numScreens ? index : 0;

        updateWaylandMonitorScaling();

        for (int id = 0; id < numScreens; ++id) {
            X11GraphicsDevice reused = old.remove(id);
            X11GraphicsDevice gd = reused != null ? reused : new X11GraphicsDevice(id);
            devices.put(id, gd);
            if (LogDisplay.ENABLED) {
                LogDisplay log = reused != null ? LogDisplay.CHANGED : LogDisplay.ADDED;
                log.log(id, gd.getBounds(), gd.getScaleFactor());
            }
        }
        // if a device was not reused it should be invalidated
        for (X11GraphicsDevice gd : old.values()) {
            if (LogDisplay.ENABLED) {
                LogDisplay.REMOVED.log(gd.getScreen(), gd.getBounds(), gd.getScaleFactor());
            }
            oldDevices.add(new WeakReference<>(gd));
        }
        // Need to notify old devices, in case the user hold the reference to it
        for (ListIterator<WeakReference<X11GraphicsDevice>> it =
             oldDevices.listIterator(); it.hasNext(); ) {
            X11GraphicsDevice gd = it.next().get();
            if (gd != null) {
                gd.invalidate(devices.get(mainScreen));
                gd.displayChanged();
            } else {
                // no more references to this device, remove it
                it.remove();
            }
        }
    }

    public Point scaleUp(X11GraphicsDevice defaultDevice, int x, int y) {
        Point p = defaultDevice == null ? null : defaultDevice.scaleUpChecked(x, y);
        if (p != null) {
            return p;
        }
        for (X11GraphicsDevice d : devices.values()) {
            if (d != defaultDevice && (p = d.scaleUpChecked(x, y)) != null) {
                return p;
            }
        }
        return defaultDevice == null ? null : defaultDevice.scaleUp(x, y);
    }

    public Point scaleDown(X11GraphicsDevice defaultDevice, int x, int y) {
        Point p = defaultDevice == null ? null : defaultDevice.scaleDownChecked(x, y);
        if (p != null) {
            return p;
        }
        for (X11GraphicsDevice d : devices.values()) {
            if (d != defaultDevice && (p = d.scaleDownChecked(x, y)) != null) {
                return p;
            }
        }
        return defaultDevice == null ? null : defaultDevice.scaleDown(x, y);
    }

    @Override
    public synchronized GraphicsDevice getDefaultScreenDevice() {
        return devices.get(mainScreen);
    }

    @Override
    public synchronized GraphicsDevice[] getScreenDevices() {
        return devices.values().toArray(new X11GraphicsDevice[0]);
    }

    public synchronized GraphicsDevice getScreenDevice(int screen) {
        return devices.get(screen);
    }

    @Override
    protected GraphicsDevice makeScreenDevice(int screennum) {
        throw new UnsupportedOperationException("This method is unused and" +
                "should not be called in this implementation");
    }

    @Override
    public boolean isDisplayLocal() {
        if (isDisplayLocal == null) {
            SunToolkit.awtLock();
            try {
                if (isDisplayLocal == null) {
                    isDisplayLocal = Boolean.valueOf(_isDisplayLocal());
                }
            } finally {
                SunToolkit.awtUnlock();
            }
        }
        return isDisplayLocal.booleanValue();
    }

    private static boolean _isDisplayLocal() {
        if (isHeadless()) {
            return true;
        }

        String isRemote = System.getProperty("sun.java2d.remote");
        if (isRemote != null) {
            return isRemote.equals("false");
        }

        int shm = checkShmExt();
        if (shm != -1) {
            return (shm == 1);
        }

        // If XServer doesn't support ShMem extension,
        // try the other way

        String display = getDisplayString();
        int ind = display.indexOf(':');
        final String hostName = display.substring(0, ind);
        if (ind <= 0) {
            // ':0' case
            return true;
        }

        InetAddress[] remAddr = null;
        Enumeration<InetAddress> locals = null;
        Enumeration<NetworkInterface> interfaces = null;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
            remAddr = InetAddress.getAllByName(hostName);
            if (remAddr == null) {
                return false;
            }
        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + hostName);
            return false;
        } catch (SocketException e1) {
            System.err.println(e1.getMessage());
            return false;
        }

        for (; interfaces.hasMoreElements();) {
            locals = interfaces.nextElement().getInetAddresses();
            for (; locals.hasMoreElements();) {
                final InetAddress localAddr = locals.nextElement();
                for (int i = 0; i < remAddr.length; i++) {
                    if (localAddr.equals(remAddr[i])) {
                        return true;
                    }
                }
            }
        }
        return false;
    }



    /**
     * Returns face name for default font, or null if
     * no face names are used for CompositeFontDescriptors
     * for this platform.
     */
    public String getDefaultFontFaceName() {

        return null;
    }

    private static native boolean pRunningXinerama();

    public boolean runningXinerama() {
        return pRunningXinerama();
    }

    private static native void updateWaylandMonitorScaling();

    /**
     * From the DisplayChangedListener interface; devices do not need
     * to react to this event.
     */
    @Override
    public void paletteChanged() {
    }

    public String[][] getHiDPIInfo() {
        X11GraphicsDevice device = null;
        synchronized (this) {
            if (devices.containsKey(0)) {
                device = devices.get(0);
            }
        }
        return device != null ? device.getDpiInfo() : null;
    }
}
