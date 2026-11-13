/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
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

package sun.awt.windows;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.DisplayMode;
import java.awt.EventQueue;
import java.awt.GraphicsDevice;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

import sun.awt.AWTAccessor;
import sun.awt.KineticScroller;
import sun.awt.MouseWheelRoundRotationsAccumulator;
import sun.awt.PeerEvent;
import sun.awt.SunToolkit;

/**
 * Converts DirectManipulation (precision touchpad) scroll notifications
 * coming from the native code into {@code MouseWheelEvent}s and performs
 * kinetic scrolling (fling) after the fingers are lifted.
 */
final class WKineticScroller {
    // Read by the native code (AwtDirectManipulation::IsEnabled).
    static final boolean ENABLED =
            Boolean.getBoolean("sun.awt.windows.directManipulation.enabled");

    private static final int PHASE_BEGIN  = 0;
    private static final int PHASE_UPDATE = 1;
    private static final int PHASE_END    = 2;

    private static final int FLING_BLOCKING_MODIFIERS_MASK =
            KineticScroller.NON_SHIFT_MODIFIERS_MASK
            | InputEvent.BUTTON1_DOWN_MASK
            | InputEvent.BUTTON2_DOWN_MASK
            | InputEvent.BUTTON3_DOWN_MASK;

    private static final double ROTATIONS_FACTOR = 0.1;
    // Keep kinetic samples in their original units: the start/stop thresholds
    // remain 175/35 logical pixels per second when the event scale changes.
    private static final double VECTOR_SCALE =
            0.08 / MouseWheelRoundRotationsAccumulator.VECTOR_LENGTH_TO_MWE_ROTATIONS_FACTOR;

    private static final KineticScroller scroller = ENABLED ? new KineticScroller() : null;

    private final WComponentPeer peer;
    private final MouseWheelRoundRotationsAccumulator xAxisWheelAccumulator =
            new MouseWheelRoundRotationsAccumulator();
    private final MouseWheelRoundRotationsAccumulator yAxisWheelAccumulator =
            new MouseWheelRoundRotationsAccumulator();
    private final Object lock = new Object();
    private double pendingDx;
    private double pendingDy;
    private int pendingX;
    private int pendingY;
    private int pendingXAbs;
    private int pendingYAbs;
    private int pendingModifiers;
    private long pendingWhen;
    private boolean flushScheduled;

    private WKineticScroller(WComponentPeer peer) {
        this.peer = peer;
    }

    static WKineticScroller createIfEnabled(WComponentPeer peer) {
        return ENABLED ? new WKineticScroller(peer) : null;
    }

    void stop() {
        scroller.stop();
    }

    /**
     * Called for every event posted by the peer to the event queue.
     */
    void onPostEvent(AWTEvent event) {
        switch (event.getID()) {
            case MouseEvent.MOUSE_PRESSED:
            case MouseEvent.MOUSE_EXITED:
            case MouseEvent.MOUSE_WHEEL:
            case KeyEvent.KEY_PRESSED:
                stop();
                break;
        }
    }

    /**
     * Called from {@code WComponentPeer.handleEvent}.
     *
     * @return {@code true} if the event must not be passed to the native code
     */
    boolean handleEvent(AWTEvent e) {
        switch (e.getID()) {
            case MouseEvent.MOUSE_PRESSED:
            case MouseEvent.MOUSE_EXITED:
            case KeyEvent.KEY_PRESSED:
                stop();
                break;
            case MouseEvent.MOUSE_WHEEL:
                // Wheel events generated from DirectManipulation have no native counterpart
                return AWTAccessor.getAWTEventAccessor().getBData(e) == null
                        && AWTAccessor.getMouseEventAccessor().isCausedByTouchEvent((MouseEvent) e);
        }
        return false;
    }

    void handleDirectScroll(double dx, double dy, int phase,
                            int x, int y, int xAbs, int yAbs, int modifiers) {
        if (peer.isDisposed()) {
            return;
        }
        final long sampleTimeMs = System.nanoTime() / 1_000_000L;
        if (phase == PHASE_BEGIN) {
            scroller.stop();
            scroller.clearSamples();
            clearPending();
            xAxisWheelAccumulator.reset();
            yAxisWheelAccumulator.reset();
        } else if (phase == PHASE_UPDATE) {
            scroller.stop();
            double filteredDx = dx;
            double filteredDy = dy;
            if (Math.abs(filteredDy) >= Math.abs(filteredDx) * 3.0) {
                filteredDx = 0.0;
            } else if (Math.abs(filteredDx) >= Math.abs(filteredDy) * 3.0) {
                filteredDy = 0.0;
            }
            if (filteredDx != 0 || filteredDy != 0) {
                final double scaledDx = filteredDx * VECTOR_SCALE;
                final double scaledDy = filteredDy * VECTOR_SCALE;
                scroller.addSample(sampleTimeMs, scaledDx, scaledDy);
                enqueueUpdate(scaledDx, scaledDy, x, y, xAbs, yAbs, modifiers);
            }
        } else if (phase == PHASE_END) {
            flushPendingNow();
            boolean flingStarted = false;
            if ((modifiers & FLING_BLOCKING_MODIFIERS_MASK) == 0) {
                final GraphicsDevice device = peer.getGraphicsConfiguration().getDevice();
                flingStarted = scroller.startFling(sampleTimeMs, () -> framePeriodNs(device),
                        (stepDx, stepDy, isLastStep) -> {
                    if (peer.isDisposed()) {
                        return false;
                    }
                    postScrollEvents(System.currentTimeMillis(), stepDx, stepDy, isLastStep,
                            x, y, xAbs, yAbs, modifiers);
                    return true;
                });
            }
            if (!flingStarted) {
                xAxisWheelAccumulator.reset();
                yAxisWheelAccumulator.reset();
            }
            scroller.clearSamples();
        }
    }

    // Called on the KineticScroller thread: getDisplayMode() locks the device, which
    // must not be done on the toolkit thread (setDisplayMode() holds it and waits for us).
    private static long framePeriodNs(GraphicsDevice device) {
        int refreshRate = DisplayMode.REFRESH_RATE_UNKNOWN;
        try {
            DisplayMode mode = device.getDisplayMode();
            if (mode != null) {
                refreshRate = mode.getRefreshRate();
            }
        } catch (RuntimeException ignored) {
            // the device may have just been removed
        }
        return KineticScroller.framePeriodNsFor(refreshRate);
    }

    private void clearPending() {
        synchronized (lock) {
            pendingDx = 0.0;
            pendingDy = 0.0;
            pendingWhen = 0L;
        }
    }

    private void enqueueUpdate(double scaledDx, double scaledDy,
                               int x, int y, int xAbs, int yAbs, int modifiers) {
        final long now = System.currentTimeMillis();
        if (EventQueue.isDispatchThread()) {
            postScrollEvents(now, scaledDx, scaledDy, false,
                    x, y, xAbs, yAbs, modifiers);
            return;
        }
        final boolean scheduleFlush;
        synchronized (lock) {
            if (!flushScheduled || pendingWhen == 0L) {
                pendingWhen = now;
            }
            pendingDx += scaledDx;
            pendingDy += scaledDy;
            pendingX = x;
            pendingY = y;
            pendingXAbs = xAbs;
            pendingYAbs = yAbs;
            pendingModifiers = modifiers;
            if (!flushScheduled) {
                flushScheduled = true;
                scheduleFlush = true;
            } else {
                scheduleFlush = false;
            }
        }
        if (scheduleFlush) {
            SunToolkit.executeOnEventHandlerThread(new PeerEvent(
                    peer.target, this::flushPendingNow, PeerEvent.ULTIMATE_PRIORITY_EVENT));
        }
    }

    private void flushPendingNow() {
        final double dx;
        final double dy;
        final int x;
        final int y;
        final int xAbs;
        final int yAbs;
        final int modifiers;
        final long savedWhen;
        synchronized (lock) {
            dx = pendingDx;
            dy = pendingDy;
            x = pendingX;
            y = pendingY;
            xAbs = pendingXAbs;
            yAbs = pendingYAbs;
            modifiers = pendingModifiers;
            savedWhen = pendingWhen;
            pendingDx = 0.0;
            pendingDy = 0.0;
            pendingWhen = 0L;
            flushScheduled = false;
        }
        if ((dx != 0.0 || dy != 0.0) && !peer.isDisposed()) {
            final long when = savedWhen != 0L ? savedWhen : System.currentTimeMillis();
            postScrollEvents(when, dx, dy, false,
                    x, y, xAbs, yAbs, modifiers);
        }
    }

    private void postScrollEvents(long when, double dx, double dy, boolean resetAccumulators,
                                  int x, int y, int xAbs, int yAbs, int modifiers) {
        final double factor = ROTATIONS_FACTOR / VECTOR_SCALE;
        final int scrollAmount = MouseWheelRoundRotationsAccumulator.VECTOR_MWE_SCROLL_AMOUNT;

        final int xSign = (int) Math.signum(dx);
        final double xPrecise = dx * factor;
        final int xRound = xAxisWheelAccumulator.accumulateFractionalRotations(xPrecise);

        final int ySign = (int) Math.signum(dy);
        final double yPrecise = dy * factor;
        final int yRound = yAxisWheelAccumulator.accumulateFractionalRotations(yPrecise);

        final double verticalPrecise;
        final int verticalRound;
        final double horizontalPrecise;
        final int horizontalRound;

        final boolean isShiftPressed = (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
        if (isShiftPressed) {
            verticalPrecise = 0;
            verticalRound = 0;
            if (xSign == ySign) {
                final double xUnits = Math.abs(xPrecise) > Math.abs(xRound) ? xPrecise : xRound;
                final double yUnits = Math.abs(yPrecise) > Math.abs(yRound) ? yPrecise : yRound;
                if (Math.abs(xUnits) > Math.abs(yUnits)) {
                    horizontalPrecise = xPrecise;
                    horizontalRound = xRound;
                } else {
                    horizontalPrecise = yPrecise;
                    horizontalRound = yRound;
                }
            } else if (yPrecise != 0) {
                horizontalPrecise = yPrecise;
                horizontalRound = yRound;
            } else {
                horizontalPrecise = xPrecise;
                horizontalRound = xRound;
            }
        } else {
            horizontalPrecise = xPrecise;
            horizontalRound = xRound;
            verticalPrecise = yPrecise;
            verticalRound = yRound;
        }

        if (resetAccumulators) {
            xAxisWheelAccumulator.reset();
            yAxisWheelAccumulator.reset();
        }

        if (verticalRound != 0 || verticalPrecise != 0) {
            postMouseWheelEvent(when, x, y, xAbs, yAbs,
                    modifiers & ~InputEvent.SHIFT_DOWN_MASK,
                    scrollAmount, verticalRound, verticalPrecise);
        }
        if (horizontalRound != 0 || horizontalPrecise != 0) {
            postMouseWheelEvent(when, x, y, xAbs, yAbs,
                    modifiers | InputEvent.SHIFT_DOWN_MASK,
                    scrollAmount, horizontalRound, horizontalPrecise);
        }
    }

    private void postMouseWheelEvent(long when, int x, int y, int xAbs, int yAbs,
                                     int modifiers, int scrollAmount,
                                     int wheelRotation, double preciseWheelRotation) {
        final Component targetComp = (Component) peer.target;
        if (targetComp == null) {
            return;
        }
        final MouseWheelEvent mwe = new MouseWheelEvent(
                targetComp,
                MouseEvent.MOUSE_WHEEL,
                when,
                modifiers,
                x, y,
                xAbs, yAbs,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                scrollAmount,
                wheelRotation,
                preciseWheelRotation);
        AWTAccessor.getMouseEventAccessor().setCausedByTouchEvent(mwe, true);
        if (EventQueue.isDispatchThread()) {
            SunToolkit.setSystemGenerated(mwe);
            AWTAccessor.getAWTEventAccessor().setPosted(mwe);
            targetComp.dispatchEvent(mwe);
        } else {
            peer.postEventImpl(mwe);
        }
    }
}
