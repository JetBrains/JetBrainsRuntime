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


import sun.util.logging.PlatformLogger;

import javax.swing.Timer;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicReference;


abstract class WLKineticScrollerBase {
    protected static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLKineticScrollerBase");

    /**
     * @apiNote this method is guaranteed to be called from EDT only.
     */
    protected abstract void notifyKineticScrollingEvent(
        long awtWhen,
        int awtX,
        int awtY,
        int awtAbsX,
        int awtAbsY,
        int awtModifiers,

        double xAxisVector,
        int xAxisSteps120Value,
        double yAxisVector,
        int yAxisSteps120Value
    );


    /**
     * The kinetic scrolling session is over: it has finished by itself or by a final external stop.
     *
     * @apiNote this method is guaranteed to be called from EDT only.
     */
    protected abstract void notifyKineticScrollingSessionFinished();


    /**
     * @return true if a new kinetic scrolling session has been launched as the result of processing the event.
     */
    boolean processPointerEvent(
        final WLPointerEvent pointerEvent,
        final int modifiers,
        final int x,
        final int y,
        final int xAbsolute,
        final int yAbsolute
    ) {
        final boolean hasScrollDelta =
            pointerEvent.xAxisHasVectorValue() || pointerEvent.xAxisHasSteps120Value() ||
            pointerEvent.yAxisHasVectorValue() || pointerEvent.yAxisHasSteps120Value();

        if (hasScrollDelta || pointerEvent.hasButtonEvent()) {
            this.stopCurrentScrollingSession(pointerEvent.hasButtonEvent());
        }

        if (pointerEvent.hasAxisSourceEvent()) {
            this.lastAxisSource = WLPointerEvent.AxisSourceType.recognizedOrNull(pointerEvent.getAxisSource());
        }
        if (!pointerEvent.hasAnyAxisLikeEvents()) {
            return false;
        }
        if (this.lastAxisSource != WLPointerEvent.AxisSourceType.FINGER) {
            this.physics.clearSamples();
            return false;
        }

        final double xAxisVectorValue = pointerEvent.xAxisHasVectorValue() ? pointerEvent.getXAxisVectorValue() : 0;
        final double yAxisVectorValue = pointerEvent.yAxisHasVectorValue() ? pointerEvent.getYAxisVectorValue() : 0;

        if (xAxisVectorValue != 0 || yAxisVectorValue != 0) {
            this.physics.addSample(
                pointerEvent.xAxisHasVectorValue() ? pointerEvent.getXAxisVectorTimestamp() : pointerEvent.getYAxisVectorTimestamp(),
                xAxisVectorValue,
                yAxisVectorValue
            );
            return false;
        }
        if (!pointerEvent.xAxisHasStopEvent() && !pointerEvent.yAxisHasStopEvent()) {
            return false;
        }
        if (this.currentScrollingSession.get() != null) {
            // The axes have been stopped by separate frames, and the first of them has already launched the session
            return false;
        }

        final long flingTimestamp =
            pointerEvent.xAxisHasStopEvent() ? pointerEvent.getXAxisStopEventTimestamp() : pointerEvent.getYAxisStopEventTimestamp();

        // Kinetic scrolling should only trigger if no mouse buttons and no key modifiers except Shift are pressed
        final boolean toStartNewScrollingSession =
            !WLPointerEvent.PointerButtonCodes.anyMatchMask(modifiers) &&
            (modifiers & NON_SHIFT_KEY_MODIFIERS_MASK) == 0 &&
            this.physics.startFling(flingTimestamp);

        this.physics.clearSamples();

        if (toStartNewScrollingSession) {
            this.startNewScrollingSession(modifiers, x, y, xAbsolute, yAbsolute);
        }
        return toStartNewScrollingSession;
    }

    /**
     * @param finalStop false if the fling is interrupted by what may be the beginning of the next scroll gesture:
     *                  only the ticks stop then, and the physics keeps the velocity of the fling
     *                  so that {@link WLKineticScrollPhysics#startFling} can add its rest to the next one.
     *
     * @apiNote this method can be called from any thread.
     */
    void stopCurrentScrollingSession(final boolean finalStop) {
        replaceCurrentScrollingSession(null);
        if (finalStop) {
            // The physics and the notification are for EDT only
            if (EventQueue.isDispatchThread()) {
                this.finishStoppedScrollingSession();
            } else {
                EventQueue.invokeLater(this::finishStoppedScrollingSession);
            }
        }
    }

    private void finishStoppedScrollingSession() {
        if (this.physics.isFlinging()) {
            this.physics.stop();
            this.notifyKineticScrollingSessionFinished();
        }
    }


    private static final int TICK_MS = 8;
    private static final int NON_SHIFT_KEY_MODIFIERS_MASK =
        KeyEvent.CTRL_DOWN_MASK |
        KeyEvent.ALT_DOWN_MASK |
        KeyEvent.ALT_GRAPH_DOWN_MASK |
        KeyEvent.META_DOWN_MASK;

    private final WLKineticScrollPhysics physics = new WLKineticScrollPhysics();
    // axis_source is not always sent in every frame, so keep last one
    private WLPointerEvent.AxisSourceType lastAxisSource = null;
    private final AtomicReference<Timer> currentScrollingSession = new AtomicReference<>(null);

    private void startNewScrollingSession(
        final int modifiers,
        final int x,
        final int y,
        final int xAbsolute,
        final int yAbsolute
    ) {
        final Timer newScrollingSession = new Timer(TICK_MS, null);
        newScrollingSession.addActionListener(new ActionListener() {
            // NB: do not use System.currentTimeMillis - it is wall time, i.e. depends on NTP steps and manual clock changes
            long previousTriggerTimestampNanos = System.nanoTime();

            @Override
            public void actionPerformed(final ActionEvent event) {
                final long currentTriggerTimestampNanos = System.nanoTime();
                final double msElapsed = (currentTriggerTimestampNanos - this.previousTriggerTimestampNanos) / 1_000_000.0;

                WLKineticScrollerBase.this.physics.advance(msElapsed);
                final double passedDistanceX = WLKineticScrollerBase.this.physics.getDeltaX();
                final double passedDistanceY = WLKineticScrollerBase.this.physics.getDeltaY();

                if (passedDistanceX != 0 || passedDistanceY != 0) {
                    WLKineticScrollerBase.this.notifyKineticScrollingEvent(
                        System.currentTimeMillis(),
                        x,
                        y,
                        xAbsolute,
                        yAbsolute,
                        modifiers,
                        passedDistanceX,
                        0,
                        passedDistanceY,
                        0
                    );
                }

                final boolean toContinue = WLKineticScrollerBase.this.physics.isFlinging();
                if (!toContinue) {
                    newScrollingSession.stop();
                    WLKineticScrollerBase.this.currentScrollingSession.compareAndSet(newScrollingSession, null);
                }

                this.previousTriggerTimestampNanos = currentTriggerTimestampNanos;
            }
        });
        this.replaceCurrentScrollingSession(newScrollingSession);
    }

    private void replaceCurrentScrollingSession(final Timer newSession) {
        final var oldScrollingSession = this.currentScrollingSession.getAndSet(newSession);
        if (oldScrollingSession != null) {
            oldScrollingSession.stop();
        }

        if (newSession != null) {
            newSession.start();
            // another thread may have replaced and stopped the session before it was started
            if (this.currentScrollingSession.get() != newSession) {
                newSession.stop();
            }
        }
    }
}
