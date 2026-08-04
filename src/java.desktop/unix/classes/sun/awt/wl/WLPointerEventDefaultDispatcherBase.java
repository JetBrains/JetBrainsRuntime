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

import java.awt.EventQueue;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Objects;


abstract class WLPointerEventDefaultDispatcherBase {
    protected static final PlatformLogger log = PlatformLogger.getLogger("sun.awt.wl.WLDefaultPointerEventDispatcherBase");


    /**
     * Accumulates fractional parts of wheel rotation steps until their absolute sum represents one or more full step(s).
     * This allows implementing smoother scrolling, e.g., the sequence of wl_pointer::axis events with values
     *   [0.2, 0.1, 0.4, 0.4] can be accumulated into 1.1=0.2+0.1+0.4+0.4, making it possible to
     *   generate a MouseWheelEvent with wheelRotation=1
     *   (instead of 4 tries to generate a MouseWheelEvent with wheelRotation=0 due to double->int conversion)
     */
    public static class MouseWheelRoundRotationsAccumulator {
        /**
         * This method is intended to accumulate fractional numbers of wheel rotations.
         *
         * @param fractionalRotations - fractional number of wheel rotations (usually got from a {@code wl_pointer::axis} event)
         * @return The number of wheel round rotations accumulated
         * @see #accumulateSteps120Rotations
         */
        public int accumulateFractionalRotations(double fractionalRotations) {
            // The code assumes that the target component ({@link WLComponentPeer#target}) never changes.
            // If it did, all the accumulating fields would have to be reset each time the target changed.

            accumulatedFractionalRotations += fractionalRotations;
            final int result = (int)accumulatedFractionalRotations;
            accumulatedFractionalRotations -= result;
            return result;
        }

        /**
         * This method is intended to accumulate 1/120 fractions of a rotation step.
         *
         * @param steps120Rotations - a number of 1/120 parts of a wheel step (so that, e.g.,
         *                            30 means one quarter of a step,
         *                            240 means two steps,
         *                            -240 means two steps in the negative direction,
         *                            540 means 4.5 steps).
         *                            Usually got from a {@code wl_pointer::axis_discrete}/{@code axis_value120} event.
         * @return The number of wheel round rotations accumulated
         * @see #accumulateFractionalRotations
         */
        public int accumulateSteps120Rotations(int steps120Rotations) {
            // The code assumes that the target component ({@link WLComponentPeer#target}) never changes.
            // If it did, all the accumulating fields would have to be reset each time the target changed.

            accumulatedSteps120Rotations += steps120Rotations;
            final int result = accumulatedSteps120Rotations / 120;
            accumulatedSteps120Rotations %= 120;
            return result;
        }

        public void reset() {
            accumulatedFractionalRotations = 0;
            accumulatedSteps120Rotations = 0;
        }


        protected double accumulatedFractionalRotations = 0;
        protected int accumulatedSteps120Rotations = 0;
    }


    public final void dispatchPointerEventInContext(
        final WLPointerEvent e,
        final WLInputState newInputState,
        final int awtPeerX,
        final int awtPeerY,
        final int awtAbsX,
        final int awtAbsY
    ) {
        final long awtWhen = System.currentTimeMillis();

        this.processPointerEnterEventIfAny(e, newInputState, awtPeerX, awtPeerY, awtAbsX, awtAbsY, awtWhen);
        this.processPointerButtonEventIfAny(e, newInputState, awtPeerX, awtPeerY, awtAbsX, awtAbsY, awtWhen);
        this.processPointerAxisEventsIfAny(e, newInputState, awtPeerX, awtPeerY, awtAbsX, awtAbsY, awtWhen);
        this.processPointerMotionEventIfAny(e, newInputState, awtPeerX, awtPeerY, awtAbsX, awtAbsY, awtWhen);
        this.processPointerLeaveEventIfAny(e, newInputState, awtPeerX, awtPeerY, awtAbsX, awtAbsY, awtWhen);
    }

    /**
     * This method is supposed to deliver a new {@link MouseEvent} in the same manner other
     * input events are delivered. For example, if {@link KeyEvent}s are delivered via some sort of
     * {@link sun.awt.SunToolkit#postEvent(sun.awt.AppContext, java.awt.AWTEvent)}, the MouseEvent should be delivered with deferral too.
     *
     * @apiNote this method is NOT guaranteed to be called on EDT.
     */
    protected abstract void notifyMouseEvent(
        int     awtId,
        long    awtWhen,
        int     awtButton,
        int     awtX,
        int     awtY,
        int     awtAbsX,
        int     awtAbsY,
        int     awtModifiers,
        int     awtClickCount,
        boolean awtIsPopupTrigger
    );

    /**
     * This method is supposed to deliver a new {@link MouseWheelEvent} in the same manner other
     * input events are delivered. For example, if {@link KeyEvent}s are delivered via some sort of
     * {@link sun.awt.SunToolkit#postEvent(sun.awt.AppContext, java.awt.AWTEvent)}, the MouseWheelEvent should be delivered with deferral too.
     *
     * @apiNote this method is NOT guaranteed to be called on EDT.
     */
    protected abstract void notifyMouseWheelEvent(
        long    awtWhen,
        int     awtX,
        int     awtY,
        int     awtAbsX,
        int     awtAbsY,
        int     awtModifiers,
        int     awtClickCount,
        boolean awtIsPopupTrigger,
        int     awtScrollType,
        int     awtScrollAmount,
        int     awtWheelRotation,
        double  awtPreciseWheelRotation
    );

    protected void notifyRawPointerAxisEvent(
        final long    awtWhen,
        final int     awtX,
        final int     awtY,
        final int     awtAbsX,
        final int     awtAbsY,
        final int     awtModifiers,
        final boolean awtIsPopupTrigger,

        final WLPointerEvent.AxisSourceType pointerAxisSource,
        final double xAxisVector,
        final int    xAxisSteps120Value,
        final double yAxisVector,
        final int    yAxisSteps120Value
    ) {
        // Current implementation is not thread-safe
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        // WLPointerEvent -> MouseWheelEvent conversion constants.
        // Please keep in mind that they're all related, so that changing one may require adjusting the others
        //   (or altering this conversion routine).

        // XToolkit uses 3 units per a wheel step, so do we here to preserve the user experience
        final int  STEPS120_MWE_SCROLL_AMOUNT = 3;
        // For touchpad scrolling, it's worth being able to scroll the minimum possible number of units (i.e. 1)
        final int    VECTOR_MWE_SCROLL_AMOUNT = 1;
        // 0.28 has experimentally been found as providing a good balance between
        //   wheel scrolling sensitivity and touchpad scrolling sensitivity
        final double VECTOR_LENGTH_TO_MWE_ROTATIONS_FACTOR = 0.28;

        // Converting the X axis Wayland values to MouseWheelEvent parameters.

        final int     xAxisDirectionSign;
        final double  xAxisMWEPreciseRotations;
        final int     xAxisMWERoundRotations;
        final int     xAxisMWEScrollAmount;

        // wl_pointer::axis_discrete/axis_value120 are preferred over wl_pointer::axis because
        //   they're closer to MouseWheelEvent by their nature.
        if (xAxisSteps120Value != 0) {
            xAxisDirectionSign       = Integer.signum(xAxisSteps120Value);
            xAxisMWEPreciseRotations = xAxisSteps120Value / 120d;
            xAxisMWERoundRotations   = xAxisWheelRoundRotationsAccumulator.accumulateSteps120Rotations(xAxisSteps120Value);
            // It would be probably better to calculate the scrollAmount taking the xAxisVector value into
            //   consideration, so that the wheel scrolling speed could be adjusted via some system settings.
            // However, neither Gnome nor KDE currently provide such a setting, making it difficult to test
            //   how well such an approach would work. So leaving it as is for now.
            xAxisMWEScrollAmount     = STEPS120_MWE_SCROLL_AMOUNT;
        } else {
            xAxisDirectionSign       = (int)Math.signum(xAxisVector);
            xAxisMWEPreciseRotations = xAxisVector * VECTOR_LENGTH_TO_MWE_ROTATIONS_FACTOR;
            xAxisMWERoundRotations   = xAxisWheelRoundRotationsAccumulator.accumulateFractionalRotations(xAxisMWEPreciseRotations);
            xAxisMWEScrollAmount     = VECTOR_MWE_SCROLL_AMOUNT;
        }

        // Converting the Y axis Wayland values to MouseWheelEvent parameters.
        // (Currently, the routine is exactly like for X axis)

        final int     yAxisDirectionSign;
        final double  yAxisMWEPreciseRotations;
        final int     yAxisMWERoundRotations;
        final int     yAxisMWEScrollAmount;

        if (yAxisSteps120Value != 0) {
            yAxisDirectionSign       = Integer.signum(yAxisSteps120Value);
            yAxisMWEPreciseRotations = yAxisSteps120Value / 120d;
            yAxisMWERoundRotations   = yAxisWheelRoundRotationsAccumulator.accumulateSteps120Rotations(yAxisSteps120Value);
            yAxisMWEScrollAmount     = STEPS120_MWE_SCROLL_AMOUNT;
        } else {
            yAxisDirectionSign       = (int)Math.signum(yAxisVector);
            yAxisMWEPreciseRotations = yAxisVector * VECTOR_LENGTH_TO_MWE_ROTATIONS_FACTOR;
            yAxisMWERoundRotations   = yAxisWheelRoundRotationsAccumulator.accumulateFractionalRotations(yAxisMWEPreciseRotations);
            yAxisMWEScrollAmount     = VECTOR_MWE_SCROLL_AMOUNT;
        }

        if (log.isLoggable(PlatformLogger.Level.FINE)) {
            log.fine(
                "dispatchPointerAxisEventToOwner: " +
                "xAxisVector={0} " +
                "xAxisSteps120Value={1} " +
                "yAxisVector={2} " +
                "yAxisSteps120Value={3} -> " +
                "xAxisDirectionSign={4} " +
                "xAxisMWEPreciseRotations={5} " +
                "xAxisMWERoundRotations={6} " +
                "xAxisMWEScrollAmount={7} " +
                "yAxisDirectionSign={8} " +
                "yAxisMWEPreciseRotations={9} " +
                "yAxisMWERoundRotations={10} " +
                "yAxisMWEScrollAmount={11}.",

                xAxisVector,
                xAxisSteps120Value,
                yAxisVector,
                yAxisSteps120Value,
                xAxisDirectionSign,
                xAxisMWEPreciseRotations,
                xAxisMWERoundRotations,
                xAxisMWEScrollAmount,
                yAxisDirectionSign,
                yAxisMWEPreciseRotations,
                yAxisMWERoundRotations,
                yAxisMWEScrollAmount
            );
        }

        // macOS's and Windows' AWT implement the following logic, so do we:
        //   Shift + a vertical scroll means a horizontal scroll.
        // AWT/Swing components are also aware of it.

        final boolean isShiftPressed = (awtModifiers & KeyEvent.SHIFT_DOWN_MASK) != 0;

        // These values decide whether a horizontal scrolling MouseWheelEvent will be created and posted
        final int    horizontalMWEScrollAmount;
        final double horizontalMWEPreciseRotations;
        final int    horizontalMWERoundRotations;

        // These values decide whether a vertical scrolling MouseWheelEvent will be created and posted
        final int    verticalMWEScrollAmount;
        final double verticalMWEPreciseRotations;
        final int    verticalMWERoundRotations;

        if (isShiftPressed) {
            // Pressing Shift makes only a horizontal scrolling MouseWheelEvent possible
            verticalMWEScrollAmount     = 0;
            verticalMWEPreciseRotations = 0;
            verticalMWERoundRotations   = 0;

            // Now we're deciding values of which axis will be used to generate a horizontal MouseWheelEvent

            if (xAxisDirectionSign == yAxisDirectionSign) {
                // The scrolling directions don't contradict each other.
                // Let's pick the more influencing axis.

                final var xAxisUnitsToScroll = xAxisMWEScrollAmount * (
                    Math.abs(xAxisMWEPreciseRotations) > Math.abs(xAxisMWERoundRotations)
                    ? xAxisMWEPreciseRotations
                    : xAxisMWERoundRotations
                );

                final var yAxisUnitsToScroll = yAxisMWEScrollAmount * (
                    Math.abs(yAxisMWEPreciseRotations) > Math.abs(yAxisMWERoundRotations)
                    ? yAxisMWEPreciseRotations
                    : yAxisMWERoundRotations
                );

                if (Math.abs(xAxisUnitsToScroll) > Math.abs(yAxisUnitsToScroll)) {
                    horizontalMWEScrollAmount     = xAxisMWEScrollAmount;
                    horizontalMWEPreciseRotations = xAxisMWEPreciseRotations;
                    horizontalMWERoundRotations   = xAxisMWERoundRotations;
                } else {
                    horizontalMWEScrollAmount     = yAxisMWEScrollAmount;
                    horizontalMWEPreciseRotations = yAxisMWEPreciseRotations;
                    horizontalMWERoundRotations   = yAxisMWERoundRotations;
                }
            } else if (yAxisMWERoundRotations != 0 || yAxisMWEPreciseRotations != 0) {
                // The scrolling directions contradict.
                // Consistently choosing the Y axis values (unless they're zero) seems to be providing the most expected UI behavior here.

                horizontalMWEScrollAmount     = yAxisMWEScrollAmount;
                horizontalMWEPreciseRotations = yAxisMWEPreciseRotations;
                horizontalMWERoundRotations   = yAxisMWERoundRotations;
            } else {
                horizontalMWEScrollAmount     = xAxisMWEScrollAmount;
                horizontalMWEPreciseRotations = xAxisMWEPreciseRotations;
                horizontalMWERoundRotations   = xAxisMWERoundRotations;
            }
        } else {
            // Shift is not pressed, so both horizontal and vertical MouseWheelEvents are possible.

            horizontalMWEScrollAmount     = xAxisMWEScrollAmount;
            horizontalMWEPreciseRotations = xAxisMWEPreciseRotations;
            horizontalMWERoundRotations   = xAxisMWERoundRotations;

            verticalMWEScrollAmount       = yAxisMWEScrollAmount;
            verticalMWEPreciseRotations   = yAxisMWEPreciseRotations;
            verticalMWERoundRotations     = yAxisMWERoundRotations;
        }

        if (verticalMWERoundRotations != 0 || verticalMWEPreciseRotations != 0) {
            assert verticalMWEScrollAmount > 0
                   : String.format("Vertical scrolling event has negative scroll amount: %d", verticalMWEScrollAmount);

            this.notifyMouseWheelEvent(
                awtWhen,
                awtX, awtY,
                awtAbsX, awtAbsY,
                // Making sure the event will cause scrolling along the vertical axis
                awtModifiers & ~KeyEvent.SHIFT_DOWN_MASK,
                1,
                awtIsPopupTrigger,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                verticalMWEScrollAmount,
                verticalMWERoundRotations,
                verticalMWEPreciseRotations
            );
        }

        if (horizontalMWERoundRotations != 0 || horizontalMWEPreciseRotations != 0) {
            assert horizontalMWEScrollAmount > 0
                   : String.format("Horizontal scrolling event has negative scroll amount: %d", horizontalMWEScrollAmount);

            this.notifyMouseWheelEvent(
                awtWhen,
                awtX, awtY,
                awtAbsX, awtAbsY,
                // Making sure the event will cause scrolling along the horizontal axis
                awtModifiers | KeyEvent.SHIFT_DOWN_MASK,
                1,
                awtIsPopupTrigger,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                horizontalMWEScrollAmount,
                horizontalMWERoundRotations,
                horizontalMWEPreciseRotations
            );
        }
    }


    /* Implementation details section */

    protected final MouseWheelRoundRotationsAccumulator xAxisWheelRoundRotationsAccumulator = new MouseWheelRoundRotationsAccumulator();
    protected final MouseWheelRoundRotationsAccumulator yAxisWheelRoundRotationsAccumulator = new MouseWheelRoundRotationsAccumulator();


    private void processPointerEnterEventIfAny(
        final WLPointerEvent e,
        final WLInputState newInputState,
        final int awtPeerX,
        final int awtPeerY,
        final int awtAbsX,
        final int awtAbsY,
        final long awtWhen
    ) {
        if (e.hasEnterEvent()) {
            this.notifyMouseEvent(
                MouseEvent.MOUSE_ENTERED,
                awtWhen,
                MouseEvent.NOBUTTON,
                awtPeerX,
                awtPeerY,
                awtAbsX,
                awtAbsY,
                newInputState.getModifiers(),
                0,
                false
            );
        }
    }

    private void processPointerButtonEventIfAny(
        final WLPointerEvent e,
        final WLInputState newInputState,
        final int awtPeerX,
        final int awtPeerY,
        final int awtAbsX,
        final int awtAbsY,
        final long awtWhen
    ) {
        if (e.hasButtonEvent()) {
            final WLPointerEvent.PointerButtonCodes buttonCode =
                WLPointerEvent.PointerButtonCodes.recognizedOrNull(e.getButtonCode());

            if (buttonCode != null) {
                final int clickCount = newInputState.getClickCount();
                final boolean isPopupTrigger = buttonCode.isPopupTrigger() && e.getIsButtonPressed();
                final int buttonChanged = buttonCode.javaCode;

                this.notifyMouseEvent(
                    e.getIsButtonPressed() ? MouseEvent.MOUSE_PRESSED : MouseEvent.MOUSE_RELEASED,
                    awtWhen,
                    buttonChanged,
                    awtPeerX, awtPeerY,
                    awtAbsX, awtAbsY,
                    newInputState.getModifiers(),
                    clickCount,
                    isPopupTrigger
                );
            }
        }
    }

    private void processPointerMotionEventIfAny(
        final WLPointerEvent e,
        final WLInputState newInputState,
        final int awtPeerX,
        final int awtPeerY,
        final int awtAbsX,
        final int awtAbsY,
        final long awtWhen
    ) {
        if (e.hasMotionEvent()) {
            int clickCount = 0;
            boolean isPopupTrigger = false;
            int buttonChanged = MouseEvent.NOBUTTON;

            if (newInputState.hasPointerButtonPressed()) {
                final WLPointerEvent.PointerButtonCodes buttonCode
                    = WLPointerEvent.PointerButtonCodes.recognizedOrNull(newInputState.pointerButtonPressedEvent().linuxCode());
                if (buttonCode == null) {
                    if (log.isLoggable(PlatformLogger.Level.WARNING)) {
                        log.warning("No button code recognized for dragging event: " + e);
                    }
                    return;
                }

                clickCount = newInputState.getClickCount();
                isPopupTrigger = buttonCode.isPopupTrigger() && e.getIsButtonPressed();
                buttonChanged = buttonCode.javaCode;
            }

            this.notifyMouseEvent(
                newInputState.hasPointerButtonPressed() ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED,
                awtWhen,
                buttonChanged,
                awtPeerX, awtPeerY,
                awtAbsX, awtAbsY,
                newInputState.getModifiers(),
                clickCount,
                isPopupTrigger
            );
        }
    }

    private void processPointerLeaveEventIfAny(
        final WLPointerEvent e,
        final WLInputState newInputState,
        final int awtPeerX,
        final int awtPeerY,
        final int awtAbsX,
        final int awtAbsY,
        final long awtWhen
    ) {
        if (e.hasLeaveEvent()) {
            this.notifyMouseEvent(
                MouseEvent.MOUSE_EXITED,
                awtWhen,
                MouseEvent.NOBUTTON,
                awtPeerX, awtPeerY,
                awtAbsX, awtAbsY,
                newInputState.getModifiers(),
                0,
                false
            );
        }
    }

    private void processPointerAxisEventsIfAny(
        final WLPointerEvent e,
        final WLInputState newInputState,
        final int awtPeerX,
        final int awtPeerY,
        final int awtAbsX,
        final int awtAbsY,
        final long awtWhen
    ) {
        // Current implementation is not thread-safe
        assert EventQueue.isDispatchThread() : "Method must only be invoked on EDT";

        if (e.hasAnyAxisLikeEvents()) {
            final WLPointerEvent.AxisSourceType axisSource = Objects.requireNonNullElse(
                e.hasAxisSourceEvent() ? WLPointerEvent.AxisSourceType.recognizedOrNull(e.getAxisSource()) : null,
                WLPointerEvent.AxisSourceType.WHEEL
            );

            final double xAxisVector = e.xAxisHasVectorValue() ? e.getXAxisVectorValue() : 0;
            final int xAxisSteps120Value = e.xAxisHasSteps120Value() ? e.getXAxisSteps120Value() : 0;

            final double yAxisVector = e.yAxisHasVectorValue() ? e.getYAxisVectorValue() : 0;
            final int yAxisSteps120Value = e.yAxisHasSteps120Value() ? e.getYAxisSteps120Value() : 0;

            this.notifyRawPointerAxisEvent(
                awtWhen,
                awtPeerX,
                awtPeerY,
                awtAbsX,
                awtAbsY,
                newInputState.getModifiers(),
                false,
                axisSource,
                xAxisVector,
                xAxisSteps120Value,
                yAxisVector,
                yAxisSteps120Value
            );
        }

        if (e.xAxisHasStopEvent()) {
            this.xAxisWheelRoundRotationsAccumulator.reset();
        }
        if (e.yAxisHasStopEvent()) {
            this.yAxisWheelRoundRotationsAccumulator.reset();
        }
    }
}
