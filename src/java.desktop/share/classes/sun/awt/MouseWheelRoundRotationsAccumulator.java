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

package sun.awt;

/**
 * Accumulates fractional parts of wheel rotation steps until their absolute sum represents one or more full step(s).
 * This allows implementing smoother scrolling, e.g., the sequence of wl_pointer::axis events with values
 *   [0.2, 0.1, 0.4, 0.4] can be accumulated into 1.1=0.2+0.1+0.4+0.4, making it possible to
 *   generate a MouseWheelEvent with wheelRotation=1
 *   (instead of 4 tries to generate a MouseWheelEvent with wheelRotation=0 due to double->int conversion)
 */
public class MouseWheelRoundRotationsAccumulator {
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
