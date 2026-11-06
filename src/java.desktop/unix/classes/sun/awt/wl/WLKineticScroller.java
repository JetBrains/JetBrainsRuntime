/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

final class WLKineticScroller {
    private static final long TICK_MS = 8;
    private static final int NON_SHIFT_MODIFIERS_MASK =
            InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;

    private final WLKineticScrollPhysics physics = new WLKineticScrollPhysics();

    private Timer timer;
    private TimerTask currentTask;
    private long lastTickNanos;
    // axis_source is not always sent in every frame, so keep last one
    private int lastAxisSource = -1;
    private WLComponentPeer targetPeer;
    private WLInputState targetState;

    void processPointerEvent(WLPointerEvent e, WLInputState state, WLComponentPeer peer) {
        final boolean hasScrollDelta = e.xAxisHasVectorValue() || e.yAxisHasVectorValue()
                || e.xAxisHasSteps120Value() || e.yAxisHasSteps120Value();
        if (hasScrollDelta || e.hasButtonEvent() || e.hasEnterEvent() || e.hasLeaveEvent()) {
            stop();
        }
        if (e.getAxisSource() >= 0) {
            lastAxisSource = e.getAxisSource();
        }
        if (!e.hasAxisEvent()) {
            return;
        }
        if (lastAxisSource != WLPointerEvent.AXIS_SOURCE_FINGER) {
            physics.clearSamples();
            return;
        }

        final double dx = e.xAxisHasVectorValue() ? e.getXAxisVectorValue() : 0;
        final double dy = e.yAxisHasVectorValue() ? e.getYAxisVectorValue() : 0;
        if (dx != 0 || dy != 0) {
            physics.addSample(e.getAxisTimestamp(), dx, dy);
        } else if (e.xAxisHasStopEvent() || e.yAxisHasStopEvent()) {
            // don't start fling when Ctrl is pressed because it is zoom, not scroll
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
        timer.schedule(currentTask, TICK_MS, TICK_MS);
    }

    private void tick(TimerTask task) {
        if (task != currentTask) {
            return;
        }
        if (WLToolkit.getInputState().peerForPointerEvents() != targetPeer) {
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
            // send axis_stop on the last event to reset wheel accumulators in peer
            peer.dispatchPointerEventInContext(
                    WLPointerEvent.newSyntheticAxisEvent(physics.getDeltaX(), physics.getDeltaY(), !continues), state);
        } catch (RuntimeException err) {
            stop();
            throw err;
        }
    }
}
