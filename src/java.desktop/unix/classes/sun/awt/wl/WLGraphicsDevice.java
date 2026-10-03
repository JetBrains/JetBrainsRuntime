/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2023, JetBrains s.r.o.. All rights reserved.
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

package sun.awt.wl;

import sun.awt.AWTAccessor;
import sun.java2d.vulkan.VKEnv;
import sun.java2d.vulkan.WLVKGraphicsConfig;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Corresponds to a Wayland output and is identified by its wlID.
 * Owns all graphics configurations associated with this device.
 * Encapsulates all the other properties of the output, such as its size
 * and location in a multi-monitor configuration.
 * Whenever any of these properties change, they are updated and
 * the GraphicsConfiguration objects get re-created to let referents know of the change.
 */
public class WLGraphicsDevice extends GraphicsDevice {
    private static final double MM_IN_INCH = 25.4;

    public enum OutputChange {
        CHANGED, ENTERED, LEFT
    }

    public static final int WL_OUTPUT_CHANGED_ORIGIN = 1;

    /**
     * The denominator of the fractions that wp_fractional_scale_v1 expresses scales in:
     * the scale a compositor applies to a surface is a multiple of 1/120.
     */
    static final int FRACTIONAL_SCALE_DENOMINATOR = 120;

    /**
     *  ID of the corresponding wl_output object received from Wayland.
     *  Only changes when the device gets invalidated.
     */
    private volatile int wlID;

    /**
     * Some human-readable name of the device that came from Wayland.
     * NOT considered part of device's identity.
     */
    private volatile String name;

    /**
     * The horizontal location of this device in the multi-monitor configuration.
     */
    private volatile int x;

    /**
     * The vertical location of this device in the multi-monitor configuration.
     */
    private volatile int y;

    /**
     * Pixel width, mostly for accounting and reporting.
     */
    private volatile int width;

    /**
     * Pixel height, mostly for accounting and reporting.
     */
    private volatile int height;

    /**
     * Logical (scaled) width in Java units.
     */
    private volatile int widthLogical;

    /**
     * Logical (scaled) height in Java units.
     */
    private volatile int heightLogical;

    /**
     * Width in millimeters.
     */
    private volatile int widthMm;

    /**
     * Height in millimeters.
     */
    private volatile int heightMm;

    /**
     * The device's integer scale factor as reported by wl_output.
     * For a fractionally scaled output this is the real scale rounded up, so it is only suitable
     * where Wayland insists on an integer scale, such as wl_surface.set_buffer_scale for cursors
     * and drag icons; see {@link #getSurfaceScale()} for the scale to render at.
     */
    private volatile int displayScale;

    /**
     * The scale the compositor applies to the surfaces on this output, i.e. the number of pixels
     * a surface-local unit occupies on the screen; a multiple of 1/120 that may be fractional.
     * It is either announced by the compositor for a surface on this output
     * (wp_fractional_scale_v1.preferred_scale) or estimated as the ratio of the output's physical size
     * to its logical one, and equals displayScale on outputs with an integer scale.
     */
    private volatile double surfaceScale;

    /**
     * Whether surfaceScale was announced by the compositor rather than estimated from the output's sizes.
     */
    private volatile boolean surfaceScaleFromCompositor;

    /**
     * The effective scale factor as determined by Java: surfaceScale unless overridden
     * with the sun.java2d.uiScale system property.
     */
    private volatile double effectiveScale;

    private volatile GraphicsConfiguration[] configs;
    private volatile WLGraphicsConfig defaultConfig; // A reference to the configs array

    /**
     * Top-level window peers that consider this device as their primary one
     * and get their graphics configuration from it
     */
    private final Set<WeakReference<WLComponentPeer>> toplevels = new HashSet<>(); // guarded by 'this'

    private WLGraphicsDevice(int id,
                             String name,
                             int x, int y,
                             int width, int height,
                             int widthLogical, int heightLogical,
                             int widthMm, int heightMm,
                             int displayScale) {
        assert width > 0 && height > 0 : String.format("Invalid device size: %dx%d", width, height);
        assert widthLogical > 0 && heightLogical > 0 : String.format("Invalid logical device size: %dx%d", widthLogical, heightLogical);
        assert widthMm > 0 && heightMm > 0 : String.format("Invalid physical device size: %dx%d", widthMm, heightMm);
        assert displayScale > 0 : String.format("Invalid display scale: %d", displayScale);

        this.wlID = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.widthLogical = widthLogical;
        this.heightLogical = heightLogical;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
        this.displayScale = displayScale;
        this.surfaceScale = surfaceScaleFrom(width, height, widthLogical, heightLogical, displayScale);
        this.effectiveScale = WLGraphicsEnvironment.effectiveScaleFrom(surfaceScale);

        makeGC();
    }

    private void makeGC() {
        GraphicsConfiguration[] newConfigs;
        WLGraphicsConfig newDefaultConfig;
        if (VKEnv.isPresentationEnabled()) {
            newConfigs = VKEnv.getDevices().flatMap(d -> d.getPresentableGraphicsConfigs().map(
                            gc -> WLVKGraphicsConfig.getConfig(gc, this)))
                    .toArray(GraphicsConfiguration[]::new);
            newDefaultConfig = (WLGraphicsConfig) newConfigs[0];
        } else {
            // TODO: Actually, Wayland may support a lot more shared memory buffer configurations, need to
            //   subscribe to the wl_shm:format event and get the list from there.
            newDefaultConfig = WLSMGraphicsConfig.getConfig(this, true);
            newConfigs = new GraphicsConfiguration[2];
            newConfigs[0] = newDefaultConfig;
            newConfigs[1] = WLSMGraphicsConfig.getConfig(this, false);
        }

        configs = newConfigs;
        defaultConfig = newDefaultConfig;
    }

    void updateConfiguration(String name,
                             int x, int y,
                             int width, int height,
                             int widthLogical, int heightLogical,
                             int widthMm, int heightMm,
                             int scale) {
        double newSurfaceScale = surfaceScaleFrom(width, height, widthLogical, heightLogical, scale);
        if (surfaceScaleFromCompositor && hasSimilarSurfaceScale(newSurfaceScale)) {
            // The estimate is within its error of the scale announced by the compositor, which is exact.
            newSurfaceScale = surfaceScale;
        } else {
            surfaceScaleFromCompositor = false;
        }
        updateConfiguration(name, x, y, width, height, widthLogical, heightLogical, widthMm, heightMm,
                scale, newSurfaceScale);
    }

    private void updateConfiguration(String name,
                                     int x, int y,
                                     int width, int height,
                                     int widthLogical, int heightLogical,
                                     int widthMm, int heightMm,
                                     int scale, double surfaceScale) {
        assert width > 0 && height > 0 : String.format("Invalid device size: %dx%d", width, height);
        assert widthLogical > 0 && heightLogical > 0 : String.format("Invalid logical device size: %dx%d", widthLogical, heightLogical);
        assert widthMm > 0 && heightMm > 0 : String.format("Invalid physical device size: %dx%d", widthMm, heightMm);
        assert scale > 0 : String.format("Invalid display scale: %d", scale);
        assert surfaceScale > 0 : String.format("Invalid surface scale: %f", surfaceScale);

        this.name = name;
        int dx = x - this.x;
        int dy = y - this.y;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.widthLogical = widthLogical;
        this.heightLogical = heightLogical;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
        this.displayScale = scale;
        this.surfaceScale = surfaceScale;
        this.effectiveScale = WLGraphicsEnvironment.effectiveScaleFrom(surfaceScale);

        configurationChanged(dx, dy);
    }

    /**
     * Sets the scale the compositor has announced (wp_fractional_scale_v1.preferred_scale) for a surface
     * on this output. It is exact and therefore takes precedence over the scale estimated from the output's sizes.
     * If the scale differs from the current one, the graphics configurations get re-created and the windows
     * on this device notified, exactly as for any other change of the output's configuration.
     */
    void setSurfaceScale(double scale) {
        assert scale > 0 : String.format("Invalid surface scale: %f", scale);

        if (!WLGraphicsEnvironment.isFractionalScaleEnabled()) return;

        surfaceScaleFromCompositor = true;
        if (hasSurfaceScale(scale)) return;

        this.surfaceScale = scale;
        this.effectiveScale = WLGraphicsEnvironment.effectiveScaleFrom(scale);

        configurationChanged(0, 0);
        WLGraphicsEnvironment.getSingleInstance().displayChanged();
    }

    private void configurationChanged(int dx, int dy) {
        // It is necessary to create new config objects whenever this device changes
        // as GraphicsConfiguration identity is used to detect changes in scale, among other things.
        makeGC();

        // It is important that by the time displayChanged() events are delivered,
        // all the peers on this device had their graphics configuration updated
        // to refer to the new ones with, perhaps, a different scale or resolution.
        // This affects various BufferStrategy that use volatile images as their buffers.

        List<WLComponentPeer> toplevelsCopy = new ArrayList<>(toplevels.size());
        synchronized (this) {
            for (var toplevel: toplevels) {
                WLComponentPeer peer = toplevel.get();
                if (peer != null) {
                    toplevelsCopy.add(peer);
                }
            }
        }

        toplevelsCopy.forEach(
                wlComponentPeer ->
                        wlComponentPeer.notifyOutputChanged(
                                this, OutputChange.CHANGED,
                                dx != 0 || dy != 0 ? WL_OUTPUT_CHANGED_ORIGIN : 0,
                                dx, dy));

    }

    /**
     * Changes all aspects of this device, including its identity to be that of the given
     * device. Only used for devices that are no longer physically present, but references
     * to which may still exist in the program.
     */
    void invalidate(WLGraphicsDevice similarDevice) {
        // Note: It is expected that all the surface this device used to host have already received
        // the 'leave' event and updated their device/graphics configurations accordingly.
        this.wlID = similarDevice.wlID;
        this.surfaceScaleFromCompositor = similarDevice.surfaceScaleFromCompositor;
        updateConfiguration(similarDevice.name,
                similarDevice.x,
                similarDevice.y,
                similarDevice.width,
                similarDevice.height,
                similarDevice.widthLogical,
                similarDevice.heightLogical,
                similarDevice.widthMm,
                similarDevice.heightMm,
                similarDevice.displayScale,
                similarDevice.surfaceScale);
    }

    /**
     * Estimates the scale the compositor applies to the surfaces on an output of the given physical
     * and logical sizes, i.e. the ratio of the two. As the logical size is an integer, the ratio is only
     * approximate and gets rounded to the granularity of wp_fractional_scale_v1 (1/120):
     * 3840 / 2560 = 1.5 for an output at 150%, 1920 / 1097 = 1.7502 -> 210 / 120 = 1.75 for one at 175%.
     * Falls back to the integer scale reported by wl_output when fractional scaling is disabled.
     */
    static double surfaceScaleFrom(int width, int height, int widthLogical, int heightLogical, int displayScale) {
        if (!WLGraphicsEnvironment.isFractionalScaleEnabled() || widthLogical <= 0 || heightLogical <= 0) {
            return displayScale;
        }
        // The larger dimension is the least affected by the rounding of the logical size
        double ratio = width >= height
                ? (double) width / widthLogical
                : (double) height / heightLogical;
        long numerator = Math.round(ratio * FRACTIONAL_SCALE_DENOMINATOR);
        return numerator > 0 ? (double) numerator / FRACTIONAL_SCALE_DENOMINATOR : displayScale;
    }

    public static WLGraphicsDevice createWithConfiguration(int id, String name,
                                                           int x, int y,
                                                           int width, int height, int widthLogical, int heightLogical,
                                                           int widthMm, int heightMm,
                                                           int scale) {
        return new WLGraphicsDevice(id, name, x, y, width, height, widthLogical, heightLogical, widthMm, heightMm, scale);
    }

    boolean hasSameNameAs(WLGraphicsDevice otherDevice) {
        var localName = name;
        var otherName = otherDevice.name;
        return localName != null && localName.equals(otherName);
    }

    boolean hasSameSizeAs(WLGraphicsDevice modelDevice) {
        var config = defaultConfig;
        var modelConfig = modelDevice.defaultConfig;
        return config != null
                && modelConfig != null
                && config.getBounds().equals(modelConfig.getBounds());
    }

    @Override
    public int getType() {
        return TYPE_RASTER_SCREEN;
    }

    @Override
    public String getIDstring() {
        return name;
    }

    @Override
    public GraphicsConfiguration[] getConfigurations() {
        // From wayland.xml, wl_output.mode event:
        // "Non-current modes are deprecated. A compositor can decide to only
        // advertise the current mode and never send other modes. Clients
        // should not rely on non-current modes."
        // So there's always the same set of configs.
        return configs.clone();
    }

    @Override
    public GraphicsConfiguration getDefaultConfiguration() {
        return defaultConfig;
    }

    int getID() {
        return wlID;
    }

    Rectangle getBounds() {
        return new Rectangle(x, y, widthLogical, heightLogical);
    }

    Rectangle getRealBounds() {
        return new Rectangle(x, y, width, height);
    }

    /**
     * Returns the integer scale of the output as reported by wl_output; for fractionally scaled outputs
     * it is the real scale rounded up. Only suitable where Wayland requires an integer scale
     * (wl_surface.set_buffer_scale), such as for cursors and drag icons.
     */
    int getDisplayScale() {
        return displayScale;
    }

    /**
     * Returns the scale the compositor applies to the surfaces on this output, i.e. the number of pixels
     * a surface-local unit occupies on the screen. It may be fractional (a multiple of 1/120) and determines
     * the size of the buffers for the compositor to show them without resampling.
     */
    double getSurfaceScale() {
        return surfaceScale;
    }

    /**
     * Whether the given scale is the same as the one the compositor applies to the surfaces on this output,
     * both being multiples of 1/120.
     */
    boolean hasSurfaceScale(double scale) {
        return Math.abs(surfaceScale - scale) * FRACTIONAL_SCALE_DENOMINATOR < 0.5;
    }

    /**
     * Whether the given scale is the same as the one the compositor applies to the surfaces on this output
     * up to the error of estimating the latter from the output's sizes, which is at most
     * one step of wp_fractional_scale_v1 (1/120).
     */
    boolean hasSimilarSurfaceScale(double scale) {
        return Math.abs(surfaceScale - scale) * FRACTIONAL_SCALE_DENOMINATOR < 1.5;
    }

    /**
     * Returns the scale Java2D renders at on this device: {@link #getSurfaceScale()} unless overridden
     * with the sun.java2d.uiScale system property.
     */
    double getEffectiveScale() {
        return effectiveScale;
    }

    int getResolution() {
        // must match the horizontal resolution to pass tests
        return getResolutionX(defaultConfig);
    }

    double getPhysicalScale() {
        Rectangle bounds = defaultConfig.getRealBounds();
        double daigInPixels = Math.sqrt(bounds.width * bounds.width + bounds.height * bounds.height);
        bounds = defaultConfig.getBounds();
        double diagInUnits = Math.sqrt(bounds.width * bounds.width + bounds.height * bounds.height);
        return daigInPixels / diagInUnits;
    }

    int getResolutionX(WLGraphicsConfig config) {
        Rectangle bounds = config.getBounds();
        if (bounds.width == 0) return 0;
        return (int)((double)bounds.width  * MM_IN_INCH / widthMm);
    }

    int getResolutionY(WLGraphicsConfig config) {
        Rectangle bounds = config.getBounds();
        if (bounds.height == 0) return 0;
        return (int)((double)bounds.height * MM_IN_INCH / heightMm);
    }

    @Override
    public boolean isFullScreenSupported() {
        return true;
    }

    public void setFullScreenWindow(Window w) {
        Window old = getFullScreenWindow();
        if (w == old) {
            return;
        }

        boolean fsSupported = isFullScreenSupported();
        if (fsSupported && old != null) {
            // enter windowed mode and restore the original display mode
            exitFullScreenExclusive(old);
        }

        super.setFullScreenWindow(w);

        if (fsSupported) {
            if (w != null) {
                enterFullScreenExclusive(w);
            } else {
                exitFullScreenExclusive(old);
            }
        }
    }

    private void enterFullScreenExclusive(Window w) {
        WLComponentPeer peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer != null) {
            peer.requestFullScreen(wlID);
        }
    }

    private void exitFullScreenExclusive(Window w) {
        WLComponentPeer peer = AWTAccessor.getComponentAccessor().getPeer(w);
        if (peer != null) {
            peer.requestUnsetFullScreen();
        }
    }

    public void addWindow(WLComponentPeer peer) {
        synchronized (this) {
            toplevels.add(new WeakReference<>(peer));
        }
    }

    public void removeWindow(WLComponentPeer peer) {
        synchronized (this) {
            toplevels.removeIf(ref -> {
                WLComponentPeer p = ref.get();
                return p == null || p == peer;
            });
        }
    }

    @Override
    public String toString() {
        var config = defaultConfig;
        return String.format("WLGraphicsDevice: '%s' id=%d at (%d, %d) with %s",
                name, wlID, x, y, config != null ? config : "<no configs>");
    }

    public Insets getInsets() {
        return new Insets(0, 0, 0, 0);
    }
}
