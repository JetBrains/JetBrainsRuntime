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

package sun.awt.wl;

import java.awt.EventQueue;
import java.awt.event.InputEvent;
import java.lang.reflect.InvocationTargetException;
import java.util.Timer;
import java.util.TimerTask;

import static sun.awt.wl.WLToolkit.getNumericProperty;

/**
 * Kinetic (inertial) touchpad scrolling: after the fingers are lifted, the scrolling continues and slows down.
 * Wayland compositors don't do that, it's up to clients.
 * The inertia is delivered to the same peer, with the same pointer location and modifiers as when the fingers
 *   were lifted (like the macOS momentum scrolling). All the methods must be invoked on EDT.
 */
final class WLKineticScroller {
    private static final String PREFIX = "sun.awt.wl.KineticScrolling.";
    private static final int NON_SHIFT_MODIFIERS_MASK =
            InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;

    private final long tickMs = (long) getNumericProperty(PREFIX + "TickMs", 8, 1, 1000);
    private final WLKineticScrollPhysics physics = new WLKineticScrollPhysics(
            (long) getNumericProperty(PREFIX + "VelocityWindowMs", 150, 1, 60_000),
            getNumericProperty(PREFIX + "Friction", 4.0, Double.MIN_VALUE, 1000),
            getNumericProperty(PREFIX + "MinStartVelocity", 50, 0, Double.MAX_VALUE),
            getNumericProperty(PREFIX + "MaxVelocity", 8000, Double.MIN_VALUE, Double.MAX_VALUE),
            getNumericProperty(PREFIX + "StopVelocity", 10, Double.MIN_VALUE, Double.MAX_VALUE));

    private Timer timer;
    private TimerTask currentTask;
    private long lastTickNanos;
    // wl_pointer::axis_source isn't guaranteed to be sent in each frame
    private int lastAxisSource = -1;
    private WLComponentPeer targetPeer;
    private WLInputState targetState;

    // Must be invoked for each pointer frame after it has been dispatched
    void processPointerEvent(WLPointerEvent e, WLInputState state, WLComponentPeer peer) {
        if (e.hasAxisEvent() || e.hasButtonEvent() || e.hasEnterEvent() || e.hasLeaveEvent()) {
            stop();
        }
        if (e.getAxisSource() >= 0) {
            lastAxisSource = e.getAxisSource();
        }
        if (!e.hasAxisEvent()) {
            return;
        }
        if (lastAxisSource != WLPointerEvent.AXIS_SOURCE_FINGER) {
            // No inertia for mouse wheels, trackpoints, etc.
            physics.clearSamples();
            return;
        }

        final double dx = e.xAxisHasVectorValue() ? e.getXAxisVectorValue() : 0;
        final double dy = e.yAxisHasVectorValue() ? e.getYAxisVectorValue() : 0;
        if (dx != 0 || dy != 0) {
            physics.addSample(e.getAxisTimestamp(), dx, dy);
        }

        if (e.xAxisHasStopEvent() || e.yAxisHasStopEvent()) {
            // Ctrl + scrolling usually means zooming, so no inertia; Shift + scrolling is still scrolling
            if (peer != null && !state.hasPointerButtonPressed() && (state.getModifiers() & NON_SHIFT_MODIFIERS_MASK) == 0
                    && physics.startFling(e.getAxisTimestamp())) {
                start(state, peer);
            }
            physics.clearSamples();
        }
    }

    void stop() {
        if (currentTask != null) {
            currentTask.cancel();
            currentTask = null;
        }
        physics.stop();
        targetPeer = null;
        targetState = null;
    }

    private void start(WLInputState state, WLComponentPeer peer) {
        targetPeer = peer;
        targetState = state;
        if (timer == null) {
            timer = new Timer("WLKineticScroller", true);
        }
        lastTickNanos = System.nanoTime();
        currentTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    EventQueue.invokeAndWait(() -> tick(this));
                } catch (InterruptedException | InvocationTargetException ignored) {
                }
            }
        };
        // The step is calculated from the actual time passed, so a busy EDT doesn't change the distance
        timer.schedule(currentTask, tickMs, tickMs);
    }

    private void tick(TimerTask task) {
        if (task != currentTask) {
            return;
        }
        if (WLToolkit.getInputState().peerForPointerEvents() != targetPeer) {
            // The window has been closed or the pointer is over another surface
            stop();
            return;
        }

        final long now = System.nanoTime();
        final boolean continues = physics.advance((now - lastTickNanos) / 1_000_000.0);
        lastTickNanos = now;
        final WLComponentPeer peer = targetPeer;
        final WLInputState state = targetState;
        if (!continues) {
            stop();
        }
        try {
            // The last event contains the axis stop events, so it resets the wheel rotations accumulators
            peer.dispatchPointerEventInContext(
                    WLPointerEvent.newSyntheticAxisEvent(physics.getDeltaX(), physics.getDeltaY(), !continues), state);
        } catch (RuntimeException err) {
            stop();
            throw err;
        }
    }
}
