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

import sun.awt.KineticScroller;

final class WLKineticScroller {
    private final KineticScroller scroller = new KineticScroller();

    // axis_source is not always sent in every frame, so keep last one
    private int lastAxisSource = -1;

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
            scroller.clearSamples();
            return;
        }

        final double dx = e.xAxisHasVectorValue() ? e.getXAxisVectorValue() : 0;
        final double dy = e.yAxisHasVectorValue() ? e.getYAxisVectorValue() : 0;
        if (dx != 0 || dy != 0) {
            scroller.addSample(e.getAxisTimestamp(), dx, dy);
        } else if (e.xAxisHasStopEvent() || e.yAxisHasStopEvent()) {
            // don't start fling when Ctrl is pressed because it is zoom, not scroll
            if (peer != null && !state.hasPointerButtonPressed()
                    && (state.getModifiers() & KineticScroller.NON_SHIFT_MODIFIERS_MASK) == 0) {
                scroller.startFling(e.getAxisTimestamp(), WLKineticScroller::framePeriodNs,
                        (deltaX, deltaY, isLastStep) -> {
                    if (WLToolkit.getInputState().peerForPointerEvents() != peer) {
                        return false;
                    }
                    // send axis_stop on the last event to reset wheel accumulators in peer
                    peer.dispatchPointerEventInContext(
                            WLPointerEvent.newSyntheticAxisEvent(deltaX, deltaY, isLastStep), state);
                    return true;
                });
            }
            scroller.clearSamples();
        }
    }

    void stop() {
        scroller.stop();
    }

    // TODO: the output refresh rate (wl_output.mode) is not propagated to WLGraphicsDevice yet,
    // so the fling runs at a fixed rate regardless of the actual display.
    private static long framePeriodNs() {
        return KineticScroller.framePeriodNsFor(KineticScroller.DEFAULT_REFRESH_RATE_HZ);
    }
}
