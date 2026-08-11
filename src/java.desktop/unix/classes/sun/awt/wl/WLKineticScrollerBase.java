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

import java.awt.event.KeyEvent;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;


// TODO: must be per-java.awt.Component for proper handling WLKineticScrollerBase#axisEvents,
//       not per-Peer or per-Window or smth else.
abstract class WLKineticScrollerBase {
    // TODO: if another fling happens while a kinetic scrolling session is executing,
    //       we may want to add the current session's rest of velocity to the new one.

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

        WLPointerEvent.AxisSourceType axisSource,
        double xAxisVector,
        int xAxisSteps120Value,
        double yAxisVector,
        int yAxisSteps120Value
    );

    void processPointerEvent(
        final WLPointerEvent pointerEvent,
        final int modifiers,
        final int x,
        final int y,
        final int xAbsolute,
        final int yAbsolute
    ) {
        // TODO: if axis_stop for one of the 2 axes is sent in a separate frame,
        //       it will cause unintentional drop of the current scrolling session.
        final boolean toStopCurrentScrollingSession =
            pointerEvent.hasAnyAxisLikeEvents()
            || (pointerEvent.hasButtonEvent() && pointerEvent.getIsButtonPressed());

        if (toStopCurrentScrollingSession) {
            this.stopCurrentScrollingSession();
        }

        final WLPointerEvent.AxisSourceType axisSource =
            pointerEvent.hasAxisSourceEvent()
            ? WLPointerEvent.AxisSourceType.recognizedOrNull(pointerEvent.getAxisSource())
            : null;

        if (axisSource != WLPointerEvent.AxisSourceType.FINGER) {
            if (pointerEvent.hasAnyAxisLikeEvents()) {
                this.axisEvents.reset();
            }
            return;
        }

        final double xAxisVectorValue;
        final long xAxisTimestamp;

        final double yAxisVectorValue;
        final long yAxisTimestamp;

        boolean toRememberNewAxisEvent = false;

        if (pointerEvent.xAxisHasVectorValue()) {
            toRememberNewAxisEvent = true;
            xAxisVectorValue = pointerEvent.getXAxisVectorValue();
            // TODO: replace with the axis event timestamp
            xAxisTimestamp = pointerEvent.getLatestTimestamp();
        } else {
            xAxisVectorValue = 0;
            // TODO: take the y axis timestamp?
            xAxisTimestamp = 0;
        }
        if (pointerEvent.yAxisHasVectorValue()) {
            toRememberNewAxisEvent = true;
            yAxisVectorValue = pointerEvent.getYAxisVectorValue();
            // TODO: replace with the axis event timestamp
            yAxisTimestamp = pointerEvent.getLatestTimestamp();
        } else {
            yAxisVectorValue = 0;
            // TODO: take the x axis timestamp?
            yAxisTimestamp = 0;
        }

        if (toRememberNewAxisEvent) {
            this.axisEvents.putNewAxisFrame(xAxisVectorValue, yAxisVectorValue, xAxisTimestamp, yAxisTimestamp);
        }

        boolean toStartNewScrollingSession = false;
        long flingTimestamp = 0;

        final int keyModifiers = modifiers & (
            KeyEvent.SHIFT_DOWN_MASK |
            KeyEvent.CTRL_DOWN_MASK |
            KeyEvent.ALT_DOWN_MASK |
            KeyEvent.ALT_GRAPH_DOWN_MASK |
            KeyEvent.META_DOWN_MASK
        );

        // Kinetic scrolling should only trigger if no other key modifiers except Shift are pressed
        // TODO: should it trigger if a mouse button is pressed?
        if ((keyModifiers | KeyEvent.SHIFT_DOWN_MASK) == KeyEvent.SHIFT_DOWN_MASK) {
            if (pointerEvent.xAxisHasStopEvent()) {
                toStartNewScrollingSession = true;
                flingTimestamp = Math.max(flingTimestamp, pointerEvent.getXAxisStopEventTimestamp());
            }
            if (pointerEvent.yAxisHasStopEvent()) {
                toStartNewScrollingSession = true;
                flingTimestamp = Math.max(flingTimestamp, pointerEvent.getYAxisStopEventTimestamp());
            }
        }

        if (toStartNewScrollingSession) {
            this.startNewScrollingSession(flingTimestamp, modifiers, x, y, xAbsolute, yAbsolute);
        } else if (pointerEvent.xAxisHasStopEvent() || pointerEvent.yAxisHasStopEvent()) {
            this.axisEvents.reset();
        }
    }

    void stopCurrentScrollingSession() {
        // TODO: make sure the implementation is thread-safe
        replaceCurrentScrollingSession(null);
    }


    private static class AxisEventsRingBuffer {
        public static final int CAPACITY = 64;
        /** (i - x axis vector, i+1 - y axis vector) */
        public final double[] xyAxisVectors = new double[2 * CAPACITY];
        /** (i - x axis vector, i+1 - y axis vector) */
        public final long[] vectorTimestamps = new long[2 * CAPACITY];


        /** [0; CAPACITY] */
        public int getLength() {
            return baseLength;
        }

        /**
         * @return an opaque value to be used with {@link #getNextIdx(int)}.
         */
        public int getFirstIdx() {
            if (getLength() < 1) {
                return -1;
            }

            final int firstBaseIdx = (nextBaseIdx - getLength() + CAPACITY) % CAPACITY;
            assert firstBaseIdx >= 0 : "firstBaseIdx < 0";
            assert firstBaseIdx < CAPACITY : "firstBaseIdx >= CAPACITY";

            final int result = 2 * firstBaseIdx;
            assert result >= 0 : "result < 0";
            assert result < xyAxisVectors.length : "result >= xyAxisVectors.length";
            assert result < vectorTimestamps.length : "result >= vectorTimestamps.length";
            assert (result % 2) == 0 : "result must not be odd";

            return result;
        }

        /**
         * @return an opaque value to be used with this method again.
         */
        public int getNextIdx(final int currentIdx) {
            final var result = (currentIdx + 2) % (2 * CAPACITY);
            assert result >= 0 : "result < 0";
            assert result < xyAxisVectors.length : "result >= xyAxisVectors.length";
            assert result < vectorTimestamps.length : "result >= vectorTimestamps.length";

            return result;
        }


        public double getXAxisVectorValue(final int idx) { return xyAxisVectors[idx]; }
        public long getXAxisVectorTimestamp(final int idx) { return vectorTimestamps[idx]; }

        public double getYAxisVectorValue(final int idx) { return xyAxisVectors[idx + 1]; }
        public long getYAxisVectorTimestamp(final int idx) { return vectorTimestamps[idx + 1]; }


        public void putNewAxisFrame(double xAxisValue, double yAxisValue, long xAxisTimestamp, long yAxisTimestamp) {
            final int realIdx = nextBaseIdx * 2;

            xyAxisVectors[realIdx] = xAxisValue;
            xyAxisVectors[realIdx + 1] = yAxisValue;
            vectorTimestamps[realIdx] = xAxisTimestamp;
            vectorTimestamps[realIdx + 1] = yAxisTimestamp;

            nextBaseIdx = (nextBaseIdx + 1) % CAPACITY;
            baseLength = Math.min(CAPACITY, baseLength + 1);
        }

        public void reset() {
            baseLength = 0;
            nextBaseIdx = 0;
        }


        /** [0; CAPACITY) */
        private int nextBaseIdx = 0;
        /** [0; CAPACITY] */
        private int baseLength = 0;
    }

    private record Vector2D(double xAxis, double yAxis) {}


    private final AxisEventsRingBuffer axisEvents = new AxisEventsRingBuffer();
    // TODO: replace with Swing Timer?
    private static volatile Timer scrollingTimer = null;
    private final AtomicReference<TimerTask> currentScrollingSession = new AtomicReference<>(null);

    private void startNewScrollingSession(
        final long flingTimestamp,
        final int modifiers,
        final int x,
        final int y,
        final int xAbsolute,
        final int yAbsolute
    ) {
        final Vector2D scrollingInitialVelocity = computeScrollingInitialVelocity(flingTimestamp);

        this.axisEvents.reset();

        // temporary
        System.err.printf("startNewScrollingSession: scrollingInitialVelocity=%s.%n", scrollingInitialVelocity);

        if (scrollingInitialVelocity.xAxis == 0 && scrollingInitialVelocity.yAxis == 0) {
            this.stopCurrentScrollingSession();
            return;
        }

        // TODO: fixate the target component for kinetic scrolling for cases when the window resizes
        //       (WLComponentPeer.notifyMouseWheelEvent reevaluates it each time)

        this.replaceCurrentScrollingSession(new TimerTask() {
            Vector2D previousVelocity = scrollingInitialVelocity;
            // NB: do not use System.currentTimeMillis - it is wall time, i.e. depends on NTP steps and manual clock changes
            long previousTriggerTimestampNanos = System.nanoTime();

            static final double DECELERATION_CONSTANT = 0.01;

            @Override
            public void run() {
                // TODO: change the friction model

                // TODO: do not recompute x and y parts independently, otherwise
                //       deceleration may make diagonal flings curve toward the dominant axis
                //       as the smaller component zeroes first

                final long currentTriggerTimestampNanos = System.nanoTime();
                final long msElapsed = (currentTriggerTimestampNanos - this.previousTriggerTimestampNanos) / 1_000_000;

                double currentVelocityX =
                    Math.signum(previousVelocity.xAxis) * (Math.abs(previousVelocity.xAxis) - DECELERATION_CONSTANT * msElapsed);
                double passedDistanceX = 0;
                if (Math.signum(currentVelocityX) != Math.signum(previousVelocity.xAxis)) {
                    currentVelocityX = 0;
                    passedDistanceX = (previousVelocity.xAxis * previousVelocity.xAxis) / (2 * DECELERATION_CONSTANT);
                } else {
                    passedDistanceX = Math.abs(previousVelocity.xAxis) * msElapsed - DECELERATION_CONSTANT * msElapsed * msElapsed / 2;
                }
                passedDistanceX *= Math.signum(previousVelocity.xAxis);

                double currentVelocityY =
                    Math.signum(previousVelocity.yAxis) * (Math.abs(previousVelocity.yAxis) - DECELERATION_CONSTANT * msElapsed);
                double passedDistanceY = 0;
                if (Math.signum(currentVelocityY) != Math.signum(previousVelocity.yAxis)) {
                    currentVelocityY = 0;
                    passedDistanceY = (previousVelocity.yAxis * previousVelocity.yAxis) / (2 * DECELERATION_CONSTANT);
                } else {
                    passedDistanceY = Math.abs(previousVelocity.yAxis) * msElapsed - DECELERATION_CONSTANT * msElapsed * msElapsed / 2;
                }
                passedDistanceY *= Math.signum(previousVelocity.yAxis);

                if (currentVelocityX == 0 && currentVelocityY == 0) {
                    this.cancel();
                    WLKineticScrollerBase.this.currentScrollingSession.compareAndSet(this, null);
                }

                if (passedDistanceX != 0 || passedDistanceY != 0) {
                    // TODO: unsafe to call from a non-EDT thread
                    WLKineticScrollerBase.this.notifyKineticScrollingEvent(
                        System.currentTimeMillis(),
                        x,
                        y,
                        xAbsolute,
                        yAbsolute,
                        modifiers,
                        WLPointerEvent.AxisSourceType.FINGER,
                        passedDistanceX,
                        0,
                        passedDistanceY,
                        0
                    );
                }

                this.previousTriggerTimestampNanos = currentTriggerTimestampNanos;
                this.previousVelocity = new Vector2D(currentVelocityX, currentVelocityY);

                // temporary
                //System.err.println("WLKineticScrollerBase: currentVelocityX=" + currentVelocityX);
                //System.err.println("WLKineticScrollerBase: passedDistanceX=" + passedDistanceX);
                System.err.println("WLKineticScrollerBase: currentVelocityY=" + currentVelocityY);
                System.err.println("WLKineticScrollerBase: passedDistanceY=" + passedDistanceY);
            }
        });
    }

    private Vector2D computeScrollingInitialVelocity(final long flingTimestamp) {
        // For now, it's just distance over the last 150ms
        // TODO: replace with the Least Squares method or a different model
        // TODO: decrease the initial speed depending on the amount of time passed between
        //       the last axis event and the axis_stop event
        // TODO: take native uint32_t timestamps overflow into account? (happens each ~50 days of uptime)

        final int AXIS_FRESH_EVENTS_TIMEOUT_MS = 150;

        // NB: it may be wrong to subtract milliseconds from the timestamp of wl_pointer.axis_stop events
        //     because the protocol does not guarantee them to be counted in milliseconds
        //     (only millisecond granularity is guaranteed).
        final long latestTimestampToBeExpired = flingTimestamp - AXIS_FRESH_EVENTS_TIMEOUT_MS;

        double xAxisDistance = 0;
        double yAxisDistance = 0;

        boolean xAxisHasExpiredVectors = false;
        long xAxisLatestExpiredVectorTimestamp = Long.MIN_VALUE;

        boolean yAxisHasExpiredVectors = false;
        long yAxisLatestExpiredVectorTimestamp = Long.MIN_VALUE;

        boolean xAxisHasFreshVectors = false;
        double xAxisOldestFreshVector = 0;
        long xAxisOldestFreshVectorTimestamp = Long.MAX_VALUE;

        boolean yAxisHasFreshVectors = false;
        double yAxisOldestFreshVector = 0;
        long yAxisOldestFreshVectorTimestamp = Long.MAX_VALUE;

        for (int axisEventsCount = this.axisEvents.getLength(), currentIdx = this.axisEvents.getFirstIdx();
             axisEventsCount > 0;
             --axisEventsCount, currentIdx = this.axisEvents.getNextIdx(currentIdx))
        {
            final var xAxisVector = axisEvents.getXAxisVectorValue(currentIdx);
            final var yAxisVector = axisEvents.getYAxisVectorValue(currentIdx);

            final var xAxisVectorTimestamp = axisEvents.getXAxisVectorTimestamp(currentIdx);
            final var yAxisVectorTimestamp = axisEvents.getYAxisVectorTimestamp(currentIdx);

            if (xAxisVectorTimestamp <= latestTimestampToBeExpired) {
                xAxisHasExpiredVectors = true;
                if (xAxisVectorTimestamp >= xAxisLatestExpiredVectorTimestamp) {
                    xAxisLatestExpiredVectorTimestamp = xAxisVectorTimestamp;
                }
            } else if (xAxisVectorTimestamp <= flingTimestamp) {
                xAxisHasFreshVectors = true;
                xAxisDistance += xAxisVector;
                if (xAxisVectorTimestamp < xAxisOldestFreshVectorTimestamp) {
                    xAxisOldestFreshVector = xAxisVector;
                    xAxisOldestFreshVectorTimestamp = xAxisVectorTimestamp;
                }
            }

            if (yAxisVectorTimestamp <= latestTimestampToBeExpired) {
                yAxisHasExpiredVectors = true;
                if (yAxisVectorTimestamp >= yAxisLatestExpiredVectorTimestamp) {
                    yAxisLatestExpiredVectorTimestamp = yAxisVectorTimestamp;
                }
            } else if (yAxisVectorTimestamp <= flingTimestamp) {
                yAxisHasFreshVectors = true;
                yAxisDistance += yAxisVector;
                if (yAxisVectorTimestamp < yAxisOldestFreshVectorTimestamp) {
                    yAxisOldestFreshVector = yAxisVector;
                    yAxisOldestFreshVectorTimestamp = yAxisVectorTimestamp;
                }
            }
        }

        if (xAxisHasExpiredVectors && xAxisHasFreshVectors) {
            final double xAxisOldestFreshVectorInterpolationMultiplier =
                (double)(xAxisOldestFreshVectorTimestamp - latestTimestampToBeExpired) /
                (xAxisOldestFreshVectorTimestamp - xAxisLatestExpiredVectorTimestamp);

            final double xAxisOldestFreshVectorInterpolated = xAxisOldestFreshVector * xAxisOldestFreshVectorInterpolationMultiplier;
            xAxisDistance -= (xAxisOldestFreshVector - xAxisOldestFreshVectorInterpolated);
        }
        if (yAxisHasExpiredVectors && yAxisHasFreshVectors) {
            final double yAxisOldestFreshVectorInterpolationMultiplier =
                (double)(yAxisOldestFreshVectorTimestamp - latestTimestampToBeExpired) /
                (yAxisOldestFreshVectorTimestamp - yAxisLatestExpiredVectorTimestamp);

            final double yAxisOldestFreshVectorInterpolated = yAxisOldestFreshVector * yAxisOldestFreshVectorInterpolationMultiplier;
            yAxisDistance -= (yAxisOldestFreshVector - yAxisOldestFreshVectorInterpolated);
        }

        return new Vector2D(xAxisDistance / AXIS_FRESH_EVENTS_TIMEOUT_MS, yAxisDistance / AXIS_FRESH_EVENTS_TIMEOUT_MS);
    }

    private static Timer getScrollingTimer() {
        var result = scrollingTimer;
        if (result == null) {
            synchronized (WLKineticScrollerBase.class) {
                if (scrollingTimer == null) {
                    scrollingTimer = new Timer(WLKineticScrollerBase.class.getName(), true);
                }
                result = scrollingTimer;
            }
        }
        return result;
    }

    private void replaceCurrentScrollingSession(final TimerTask newSession) {
        final var oldScrollingSession = this.currentScrollingSession.getAndSet(newSession);
        if (oldScrollingSession != null) {
            oldScrollingSession.cancel();
        }

        if (newSession != null) {
            getScrollingTimer().scheduleAtFixedRate(newSession, 15, 15);
            if (this.currentScrollingSession.get() != newSession) {
                newSession.cancel();
            }
        }
    }
}
