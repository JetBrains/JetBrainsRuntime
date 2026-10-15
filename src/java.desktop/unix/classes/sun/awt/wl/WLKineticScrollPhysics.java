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

import java.util.ArrayDeque;

/**
 * The physics of kinetic (inertial) scrolling, modelled after GTK4.
 * The initial velocity {@code v0} is calculated from the scroll deltas recorded within {@code velocityWindowMs}
 *   before the fingers are lifted; then it decays exponentially: {@code v(t) = v0 * e^(-f*t)},
 *   so the position is {@code x(t) = v0/f * (1 - e^(-f*t))} and the whole distance is {@code v0/f}.
 * Distances are in surface-local units, velocities are in such units per second, timestamps are unsigned
 *   32-bit values in milliseconds (as provided by Wayland), so they may wrap around.
 */
public final class WLKineticScrollPhysics {
    private static final long UINT32_MASK = 0xFFFFFFFFL;

    private final long velocityWindowMs;
    private final double friction;
    private final double minStartVelocity;
    private final double maxVelocity;
    private final double stopVelocity;

    // {timeMs, dx, dy} of the recent scroll deltas, the oldest first
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();

    // The initial velocity of the current fling (zero if there's no fling)
    private double velocityX;
    private double velocityY;
    private double elapsedSeconds;
    private double positionX;
    private double positionY;
    private double deltaX;
    private double deltaY;

    public WLKineticScrollPhysics(long velocityWindowMs, double friction,
                                  double minStartVelocity, double maxVelocity, double stopVelocity) {
        this.velocityWindowMs = velocityWindowMs;
        this.friction = friction;
        this.minStartVelocity = minStartVelocity;
        this.maxVelocity = maxVelocity;
        this.stopVelocity = stopVelocity;
    }

    public void addSample(long timeMs, double dx, double dy) {
        dropSamplesOlderThanWindow(timeMs);
        samples.addLast(new double[] {timeMs & UINT32_MASK, dx, dy});
    }

    public void clearSamples() {
        samples.clear();
    }

    /**
     * Starts a new fling (stopping the current one) if the samples recorded right before {@code stopTimeMs}
     *   are fast enough.
     */
    public boolean startFling(long stopTimeMs) {
        stop();
        dropSamplesOlderThanWindow(stopTimeMs);
        if (samples.size() < 2) {
            return false;
        }
        final long intervalMs = ((long) samples.getLast()[0] - (long) samples.getFirst()[0]) & UINT32_MASK;
        if (intervalMs == 0) {
            return false;
        }

        // The delta of the first sample has been travelled before its timestamp, so it's not counted
        double distanceX = -samples.getFirst()[1];
        double distanceY = -samples.getFirst()[2];
        for (double[] sample : samples) {
            distanceX += sample[1];
            distanceY += sample[2];
        }
        final double vx = distanceX * 1000 / intervalMs;
        final double vy = distanceY * 1000 / intervalMs;
        final double v = Math.hypot(vx, vy);
        if (v == 0 || v < minStartVelocity) {
            return false;
        }
        final double clamp = Math.min(1, maxVelocity / v);
        velocityX = vx * clamp;
        velocityY = vy * clamp;
        return true;
    }

    /**
     * Moves the fling forward; the movement is available via {@link #getDeltaX()} and {@link #getDeltaY()}.
     * The last step makes the whole distance equal to {@code v0/f}.
     *
     * @return {@code false} if the fling has finished (including during this call)
     */
    public boolean advance(double elapsedMs) {
        deltaX = 0;
        deltaY = 0;
        if (!isFlinging()) {
            return false;
        }

        elapsedSeconds += Math.max(0, elapsedMs) / 1000;
        final double decay = Math.exp(-friction * elapsedSeconds);
        final boolean finished = Math.hypot(velocityX, velocityY) * decay < stopVelocity;
        final double travelled = finished ? 1 : 1 - decay;
        deltaX = velocityX / friction * travelled - positionX;
        deltaY = velocityY / friction * travelled - positionY;
        positionX += deltaX;
        positionY += deltaY;
        if (finished) {
            stop();
        }
        return !finished;
    }

    public double getDeltaX() {
        return deltaX;
    }

    public double getDeltaY() {
        return deltaY;
    }

    public boolean isFlinging() {
        return velocityX != 0 || velocityY != 0;
    }

    public void stop() {
        velocityX = 0;
        velocityY = 0;
        elapsedSeconds = 0;
        positionX = 0;
        positionY = 0;
    }

    private void dropSamplesOlderThanWindow(long timeMs) {
        while (!samples.isEmpty() && ((timeMs - (long) samples.getFirst()[0]) & UINT32_MASK) > velocityWindowMs) {
            samples.removeFirst();
        }
    }
}
