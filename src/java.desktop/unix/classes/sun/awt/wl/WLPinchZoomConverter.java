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

/**
 * Converts the cumulative scale of a pinch gesture into mouse wheel rotations (like on Windows).
 * Zooming in produces negative rotations, zooming out produces positive ones; the scale is logarithmic,
 *   so zooming in 2x and out 2x give the same number of rotations of opposite signs.
 * Fractional rotations are accumulated until they add up to a whole one.
 */
public final class WLPinchZoomConverter {
    private final double rotationsPerLogScale;

    private double prevTotalRotations;
    private int reportedRoundRotations;
    private int roundRotations;
    private double preciseRotations;

    public WLPinchZoomConverter(double rotationsPerLogScale) {
        this.rotationsPerLogScale = rotationsPerLogScale;
    }

    public void reset() {
        prevTotalRotations = 0;
        reportedRoundRotations = 0;
    }

    /**
     * @param cumulativeScale the scale relative to the beginning of the gesture
     * @return {@code false} if the scale is invalid or hasn't changed (the state isn't changed then)
     */
    public boolean update(double cumulativeScale) {
        if (!(cumulativeScale > 0) || Double.isInfinite(cumulativeScale)) {
            return false;
        }

        // Calculating from the cumulative value prevents accumulating floating point errors
        final double totalRotations = -Math.log(cumulativeScale) * rotationsPerLogScale;
        if (totalRotations == prevTotalRotations) {
            return false;
        }

        preciseRotations = totalRotations - prevTotalRotations;
        prevTotalRotations = totalRotations;
        roundRotations = (int) (totalRotations - reportedRoundRotations);
        reportedRoundRotations += roundRotations;
        return true;
    }

    public int getRoundRotations() {
        return roundRotations;
    }

    public double getPreciseRotations() {
        return preciseRotations;
    }
}
