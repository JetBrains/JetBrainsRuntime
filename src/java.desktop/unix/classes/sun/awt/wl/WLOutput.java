/*
 * Copyright 2026 JetBrains s.r.o.
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

import sun.awt.SunToolkit;
import sun.util.logging.PlatformLogger;

import java.awt.AWTError;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

public class WLOutput {
    private static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLOutput");

    private long nativePtr = 0; // guarded by awt lock
    private final int registryId;
    private final int uniqueId;
    private final int wlOutputVersion;
    private final int xdgOutputVersion;

    private boolean commitPending = false;
    private volatile boolean disconnected = false;
    private boolean xdgDataReceived = false;
    private volatile WLOutputState currentState = null;
    private final PendingState pendingState = new PendingState();

    private static native void initIDs();
    private static native void nativeInitialize();
    private static native void nativeDestroy(long nativePtr);

    public static final int WL_OUTPUT_TRANSFORM_NORMAL = 0;
    public static final int WL_OUTPUT_TRANSFORM_90 = 1;
    public static final int WL_OUTPUT_TRANSFORM_180 = 2;
    public static final int WL_OUTPUT_TRANSFORM_270 = 3;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED = 4;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED_90 = 5;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED_180 = 6;
    public static final int WL_OUTPUT_TRANSFORM_FLIPPED_270 = 7;

    public static final int WL_OUTPUT_MODE_CURRENT = 0x01;
    public static final int WL_OUTPUT_MODE_PREFERRED = 0x02;

    private static final double MM_PER_INCH = 25.4;
    private static final int DEFAULT_DPI = 92;

    static {
        initIDs();
    }

    static void initialize() {
        assert !WLToolkit.isInitialized();

        nativeInitialize();

        // Register a roundtrip to wait for "done" events for initial set of outputs.
        // By the time this callback triggers, we have already called commit/commitLater.
        // It's okay if we deferred with commitLater, because that still increased the count of pending syncs,
        // and WLToolkit initialization will wait for all of them.
        WLToolkit.syncCallback(() -> {});
    }

    private static int nextUniqueId = 1;
    private static final List<WLOutput> allOutputs = new ArrayList<>();

    public static List<WLOutput> getAllOutputs() {
        synchronized (allOutputs) {
            return new ArrayList<>(allOutputs);
        }
    }

    // called from native on Wayland dispatch thread (main thread during init, then EDT)
    private WLOutput(long nativePtr, int registryId, int wlOutputVersion, int xdgOutputVersion) {
        if (wlOutputVersion < 2) {
            log.severe("Unsupported wl_output version: " + wlOutputVersion);
            throw new AWTError("Unsupported wl_output version: " + wlOutputVersion);
        }

        this.nativePtr = nativePtr;
        this.registryId = registryId;
        this.wlOutputVersion = wlOutputVersion;
        this.xdgOutputVersion = xdgOutputVersion;
        this.uniqueId = nextUniqueId++;
    }

    private static Dimension transformDimension(Dimension dimension, int transform) {
        if (dimension == null) {
            return null;
        }

        return switch (transform) {
            case WLOutput.WL_OUTPUT_TRANSFORM_90,
                 WLOutput.WL_OUTPUT_TRANSFORM_FLIPPED_90,
                 WLOutput.WL_OUTPUT_TRANSFORM_270,
                 WLOutput.WL_OUTPUT_TRANSFORM_FLIPPED_270 ->
                    new Dimension(dimension.height, dimension.width);
            default -> dimension;
        };
    }

    @Override
    public String toString() {
        return String.format("WLOutput[id=%d, wlOutputVersion=%d, xdgOutputVersion=%d]",
                uniqueId, wlOutputVersion, xdgOutputVersion);
    }

    public long getNativePtr() {
        assert SunToolkit.isAWTLockHeldByCurrentThread();
        return nativePtr;
    }

    public int getWLOutputVersion() {
        return wlOutputVersion;
    }

    public int getXdgOutputVersion() {
        return xdgOutputVersion;
    }

    public int getUniqueId() {
        return uniqueId;
    }

    public WLOutputState getState() {
        return currentState;
    }

    public boolean isConfigured() {
        return currentState != null;
    }

    public boolean isDisconnected() {
        return disconnected;
    }

    public void destroy() {
        if (WLToolkit.isInitialized()) {
            assert SunToolkit.isAWTLockHeldByCurrentThread();
        }
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    private void commit() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.commit(id=%d, commitPending=%b, disconnected=%b)", uniqueId, commitPending, disconnected));
        }

        commitPending = false;

        if (disconnected) {
            return;
        }

        WLOutputState prevState = currentState;
        WLOutputState newState = pendingState.build();
        if (newState == null) {
            log.warning(String.format("Output id=%d: incomplete configuration, update ignored (compositor bug?)", uniqueId));
            return;
        }
        currentState = newState;

        if (Objects.equals(prevState, currentState)) {
            return;
        }

        WLGraphicsEnvironment.getSingleInstance().notifyOutputConfigured(this, currentState, prevState);
    }

    private void commitLater() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.commitLater(id=%d, commitPending=%b)", uniqueId, commitPending));
        }
        if (commitPending) {
            return;
        }
        commitPending = true;
        WLToolkit.syncCallback(this::commit);
    }

    // called from native
    private static void handleGlobalRemove(int registryId) {
        WLOutput output = null;
        synchronized (allOutputs) {
            output = allOutputs.stream().filter(it -> it.registryId == registryId).findFirst().orElse(null);
            if (output == null) {
                return;
            }
            allOutputs.remove(output);
        }
        output.handleDisconnected();
    }

    // called from native after constructor
    private void handleConnected() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("Output connected, id=%d, registryId=%d, wlOutputVersion=%d, xdgOutputVersion=%d",
                    uniqueId, registryId, wlOutputVersion, xdgOutputVersion));
        }
        synchronized (allOutputs) {
            allOutputs.add(this);
        }
    }

    private void handleDisconnected() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("Output disconnected, id=%d", uniqueId));
        }
        disconnected = true;
        WLGraphicsEnvironment.getSingleInstance().notifyOutputDisconnected(this);
    }

    // "wl_output.geometry", called from native
    private void handleGeometry(
            int x, int y,
            int physicalWidth, int physicalHeight,
            int subpixel, String make, String model, int transform) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format(
                    "WLOutput.handleGeometry(id=%d, x=%d, y=%d, physicalWidth=%d, physicalHeight=%d, subpixel=%d, make=\"%s\", model=\"%s\", transform=%d)",
                    uniqueId, x, y, physicalWidth, physicalHeight, subpixel, make, model, transform));
        }
        if (physicalWidth > 0 && physicalHeight > 0) {
            pendingState.setPhysicalSizeMm(new Dimension(physicalWidth, physicalHeight));
        } else {
            pendingState.setPhysicalSizeMm(null);
        }
        pendingState.setMake(make);
        pendingState.setModel(model);
        pendingState.setTransform(transform);
        pendingState.setWlPosition(new Point(x, y));
    }

    // "wl_output.mode", called from native
    private void handleMode(int flags, int width, int height, int refresh) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleMode(id=%d, flags=%d, width=%d, height=%d, refresh=%d)",
                    uniqueId, flags, width, height, refresh));
        }
        if ((flags & WL_OUTPUT_MODE_CURRENT) != 0 && width > 0 && height > 0) {
            pendingState.setPixelSize(new Dimension(width, height));
            pendingState.setRefreshMilliHz(refresh);
        }
    }

    // "wl_output.done", called from native
    private void handleDone() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleDone(id=%d)", uniqueId));
        }
        if (xdgOutputVersion == 0 || (xdgOutputVersion >= 3 && xdgDataReceived)) {
            commit();
        } else {
            commitLater();
        }
    }

    // "wl_output.scale", called from native
    private void handleScale(int factor) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleScale(id=%d, factor=%d)", uniqueId, factor));
        }
        pendingState.setScale(factor);
    }

    // "wl_output.name", called from native
    private void handleName(String name) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleName(id=%d, name=\"%s\")", uniqueId, name));
        }
        pendingState.setName(name);
    }

    // "wl_output.description", called from native
    private void handleDescription(String description) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleDescription(id=%d, description=\"%s\")", uniqueId, description));
        }
        pendingState.setDescription(description);
    }

    // "zxdg_output_v1.logical_position", called from native
    private void handleLogicalPosition(int x, int y) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleLogicalPosition(id=%d, x=%d, y=%d)", uniqueId, x, y));
        }
        xdgDataReceived = true;
        pendingState.setXdgLogicalPosition(new Point(x, y));
    }

    // "zxdg_output_v1.logical_size", called from native
    private void handleLogicalSize(int width, int height) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleLogicalSize(id=%d, width=%d, height=%d)", uniqueId, width, height));
        }
        xdgDataReceived = true;
        pendingState.setLogicalSize(new Dimension(width, height));
    }

    // "zxdg_output_v1.done", called from native
    private void handleXdgDone() {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleXdgDone(id=%d)", uniqueId));
        }
        xdgDataReceived = true;
        if (xdgOutputVersion < 3) {
            commitLater();
        }
    }

    // "zxdg_output_v1.name", called from native
    private void handleXdgName(String name) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleXdgName(id=%d, name=\"%s\")", uniqueId, name));
        }
        xdgDataReceived = true;
        pendingState.setName(name);
    }

    // "zxdg_output_v1.description", called from native
    private void handleXdgDescription(String description) {
        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(String.format("WLOutput.handleXdgDescription(id=%d, description=\"%s\")", uniqueId, description));
        }
        xdgDataReceived = true;
        pendingState.setDescription(description);
    }

    private static class PendingState {
        private String name = null;
        private String description = null;
        private String make = null;
        private String model = null;
        private Dimension physicalSizeMm = null;
        private Dimension pixelSize = null;
        private Point wlPosition = null;
        private Point xdgLogicalPosition = null;
        private Dimension logicalSize = null;
        private int transform = WL_OUTPUT_TRANSFORM_NORMAL;
        private int scale = 1;
        private int refreshMilliHz = 0;

        public void setName(String name) {
            this.name = name;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public void setMake(String make) {
            this.make = make;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public void setPhysicalSizeMm(Dimension physicalSizeMm) {
            this.physicalSizeMm = physicalSizeMm;
        }

        public void setPixelSize(Dimension modeSizePixels) {
            this.pixelSize = modeSizePixels;
        }

        public void setWlPosition(Point wlPosition) {
            this.wlPosition = wlPosition;
        }

        public void setXdgLogicalPosition(Point xdgLogicalPosition) {
            this.xdgLogicalPosition = xdgLogicalPosition;
        }

        public void setLogicalSize(Dimension logicalSize) {
            this.logicalSize = logicalSize;
        }

        public void setTransform(int transform) {
            this.transform = transform;
        }

        public void setScale(int scale) {
            this.scale = scale;
        }

        public void setRefreshMilliHz(int refreshMilliHz) {
            this.refreshMilliHz = refreshMilliHz;
        }

        public WLOutputState build() {
            Dimension pixelSize = transformDimension(this.pixelSize, this.transform);
            if (pixelSize == null) {
                if (this.logicalSize != null) {
                    pixelSize = new Dimension(this.logicalSize.width * this.scale, this.logicalSize.height * this.scale);
                } else {
                    return null;
                }
            }

            Point logicalPosition = this.xdgLogicalPosition;
            if (logicalPosition == null) {
                logicalPosition = this.wlPosition;
            }
            if (logicalPosition == null) {
                logicalPosition = new Point(0, 0);
            }
            Dimension logicalSize = this.logicalSize;

            // Some compositors (Mutter in non-fractional scaling mode) use physical pixels for global compositor space.
            // On those configurations, xdg_output.logical_size equals actual pixel resolution of the output.
            // In this situation, we need to fix up logical bounds on our end.
            //
            // NOTE: we explicitly don't apply this division to the logicalPosition, because doing so can cause
            // monitor coordinates to overlap in the Java space. Without the division, there could be gaps
            // between the monitors in the Java space, but that's less bad by comparison.
            final boolean logicalSizeNeedsFixup = pixelSize.equals(logicalSize) && this.scale > 1;

            if (logicalSize == null || logicalSizeNeedsFixup) {
                logicalSize = new Dimension(
                        (int) Math.ceil((double) pixelSize.width / this.scale),
                        (int) Math.ceil((double) pixelSize.height / this.scale)
                );
            }

            Rectangle compositorBounds = new Rectangle(logicalPosition,
                    this.logicalSize != null ? this.logicalSize : logicalSize);

            Dimension physicalSizeMm = transformDimension(this.physicalSizeMm, this.transform);
            Dimension dpi;
            if (physicalSizeMm == null) {
                physicalSizeMm = new Dimension(
                        (int) Math.ceil(MM_PER_INCH * logicalSize.width / DEFAULT_DPI),
                        (int) Math.ceil(MM_PER_INCH * logicalSize.height / DEFAULT_DPI)
                );
                dpi = new Dimension(DEFAULT_DPI, DEFAULT_DPI);
            } else {
                dpi = new Dimension(
                        (int) ((double)logicalSize.width * MM_PER_INCH / physicalSizeMm.width),
                        (int) ((double)logicalSize.height * MM_PER_INCH / physicalSizeMm.height)
                );
            }

            return new WLOutputState(
                    this.name, this.description, this.make, this.model,
                    physicalSizeMm.width, physicalSizeMm.height,
                    compositorBounds.x, compositorBounds.y,
                    compositorBounds.width, compositorBounds.height,
                    pixelSize.width, pixelSize.height,
                    logicalPosition.x, logicalPosition.y,
                    logicalSize.width, logicalSize.height,
                    dpi.width, dpi.height,
                    this.scale,
                    WLGraphicsEnvironment.effectiveScaleFrom(this.scale),
                    this.refreshMilliHz
            );
        }
    }

    // Converts screencast stream bounds (in compositor coordinates, as reported by screencast portal)
    // into Robot's absolute device space, in the same manner as SunGraphicsEnvironment.toDeviceSpaceAbs().
    // Returns null if there's not exactly one output matching these bounds.
    // Called from native.
    private static int[] toDeviceSpaceBounds(int x, int y, int width, int height) {
        Rectangle compositorBounds = new Rectangle(x, y, width, height);
        List<WLOutputState> states = getAllOutputs().stream()
                .map(WLOutput::getState)
                .filter(Objects::nonNull)
                .filter(s -> s.compositorBounds().equals(compositorBounds))
                .toList();
        if (states.size() != 1) {
            return null;
        }
        WLOutputState state = states.getFirst();
        Rectangle deviceSpaceBounds = state.deviceSpaceBounds();
        return new int[]{
                deviceSpaceBounds.x,
                deviceSpaceBounds.y,
                deviceSpaceBounds.width,
                deviceSpaceBounds.height,
        };
    }

    // Converts a point relative to an output's device space bounds into the coordinate space of
    // that output's screencast stream.
    // Returns null if no output has these bounds.
    // Called from native.
    private static int[] toStreamSpaceCoords(int x, int y, int boundsX, int boundsY, int boundsWidth, int boundsHeight) {
        Rectangle deviceSpaceBounds = new Rectangle(boundsX, boundsY, boundsWidth, boundsHeight);
        WLOutputState state = getAllOutputs().stream()
                .map(WLOutput::getState)
                .filter(Objects::nonNull)
                .filter(s -> s.deviceSpaceBounds().equals(deviceSpaceBounds))
                .findFirst()
                .orElse(null);
        if (state == null) {
            return null;
        }
        return new int[]{
                (int) Math.floor((double) x * state.compositorWidth() / boundsWidth),
                (int) Math.floor((double) y * state.compositorHeight() / boundsHeight),
        };
    }
}
